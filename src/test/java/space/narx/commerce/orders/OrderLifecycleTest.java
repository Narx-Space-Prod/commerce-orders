package space.narx.commerce.orders;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static space.narx.commerce.orders.TestData.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.services.sqs.SqsClient;

class OrderLifecycleTest {

  JdbcTemplate db;
  AccountsController accounts;
  OrdersController controller;
  HttpServer cart;
  AtomicReference<String> response = new AtomicReference<>(
    "{\"items\":[{\"productId\":\"mug\",\"quantity\":1}]}"
  );
  final OrdersController.Delivery delivery = new OrdersController.Delivery(
    "A",
    "1 Main Street",
    "Kolkata",
    "700001",
    "India"
  );

  @BeforeEach
  void setup() throws Exception {
    db = database();
    accounts = administrator();
    cart = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    cart.createContext("/", exchange -> {
      byte[] body = response.get().getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    cart.start();
    controller = new OrdersController(
      db,
      JSON,
      accounts,
      Optional.empty(),
      "",
      "http://127.0.0.1:" + cart.getAddress().getPort()
    );
    when(db.queryForObject("SELECT 1", Integer.class)).thenReturn(1);
    when(db.queryForList(startsWith("SELECT id,name,price"))).thenReturn(
      List.of(
        Map.of(
          "id",
          "mug",
          "name",
          "Mug",
          "price",
          100,
          "currency",
          "INR",
          "stock",
          8,
          "active",
          true
        )
      )
    );
  }

  @AfterEach
  void stop() {
    cart.stop(0);
  }

  @Test
  void checkoutUsesServerPricesAndReturnsTheDurableOrder() {
    when(db.queryForList(startsWith("SELECT id,total,currency"), any(Object[].class))).thenReturn(
      List.of(),
      List.of(order())
    );
    var created = controller.create(TOKEN, "checkout-1", "request-1", TOKEN, delivery);
    assertTrue(((tools.jackson.databind.JsonNode) created.get("items")).isArray());
    assertEquals(100, created.get("total"));
    verify(db).update(startsWith("UPDATE products SET stock=stock-"), eq(1), eq("mug"), eq(1));
    verify(db).update(
      startsWith("INSERT INTO orders"),
      any(),
      eq(TOKEN),
      eq("checkout-1"),
      eq(100),
      eq("INR"),
      contains("Mug"),
      eq(ID),
      contains("Kolkata"),
      eq("payment_pending"),
      eq("unpaid"),
      eq(true)
    );
  }

  @Test
  void replayReturnsOriginalOrderWithoutReservingStockAgain() {
    when(db.queryForList(startsWith("SELECT id,total,currency"), any(Object[].class))).thenReturn(
      List.of(order())
    );
    assertEquals(
      ID,
      controller.create(TOKEN, "checkout-1", "request-1", TOKEN, delivery).get("id")
    );
    verify(db, never()).update(startsWith("UPDATE products"), any(Object[].class));
  }

  @Test
  void emptyInvalidAndUnavailableCartsCannotCreateOrders() {
    for (String body : List.of(
      "{\"items\":[]}",
      "{\"items\":{}}",
      "{\"items\":[{\"productId\":\"missing\",\"quantity\":1}]}",
      "{\"items\":[{\"productId\":\"mug\",\"quantity\":0}]}",
      "not-json"
    )) {
      response.set(body);
      assertThrows(ResponseStatusException.class, () ->
        controller.create(TOKEN, "checkout-1", "request-1", TOKEN, delivery)
      );
    }
    verify(db, never()).update(startsWith("INSERT INTO orders"), any(Object[].class));
  }

  @Test
  void stockFailureAndCompetingIdempotencyKeyAreExplicitConflicts() {
    when(db.update(startsWith("UPDATE products SET stock=stock-"), any(Object[].class))).thenReturn(
      0
    );
    assertEquals(
      "out_of_stock",
      assertThrows(ResponseStatusException.class, () ->
        controller.create(TOKEN, "checkout-1", "r", TOKEN, delivery)
      ).getReason()
    );
    when(db.update(startsWith("UPDATE products SET stock=stock-"), any(Object[].class))).thenReturn(
      1
    );
    doThrow(new DuplicateKeyException("duplicate"))
      .when(db)
      .update(startsWith("INSERT INTO orders"), any(Object[].class));
    assertEquals(
      "idempotency_key_in_use",
      assertThrows(ResponseStatusException.class, () ->
        controller.create(TOKEN, "checkout-1", "r", TOKEN, delivery)
      ).getReason()
    );
  }

  @Test
  void historyAndReceiptAccessAreScopedAndPaginationIsBounded() {
    var rows = new ArrayList<Map<String, Object>>();
    for (int i = 0; i < 21; i++) rows.add(order());
    when(db.queryForList(startsWith("SELECT id,total,currency"), any(Object[].class))).thenReturn(
      rows
    );
    var page = controller.list(TOKEN, null, 0);
    assertEquals(true, page.get("hasMore"));
    assertEquals(20, ((List<?>) page.get("orders")).size());
    controller.list(TOKEN, TOKEN, 1);
    verify(accounts).customer(TOKEN);
    assertThrows(ResponseStatusException.class, () -> controller.list(TOKEN, null, -1));
    when(db.queryForList(startsWith("SELECT id,total,currency"), any(Object[].class))).thenReturn(
      List.of(order())
    );
    when(db.queryForList(startsWith("SELECT id,amount,currency"), any(Object[].class))).thenReturn(
      List.of(Map.of("id", ID))
    );
    assertNotNull(controller.read(ID, TOKEN, TOKEN).get("receipt"));
    controller.read(ID, TOKEN, null);
    when(db.queryForList(startsWith("SELECT id,total,currency"), any(Object[].class))).thenReturn(
      List.of()
    );
    assertThrows(ResponseStatusException.class, () -> controller.read(ID, TOKEN, TOKEN));
  }

  @Test
  void healthAndInputValidationDoNotAcceptBrokenDependencies() {
    assertEquals("ok", controller.live().get("status"));
    assertEquals("ok", controller.ready().get("status"));
    var queue = mock(SqsClient.class);
    doThrow(new IllegalStateException("offline"))
      .when(queue)
      .getQueueAttributes(any(java.util.function.Consumer.class));
    var unavailable = new OrdersController(
      db,
      JSON,
      accounts,
      Optional.of(queue),
      "queue",
      "http://localhost"
    );
    assertThrows(ResponseStatusException.class, unavailable::ready);
    assertThrows(ResponseStatusException.class, () -> OrdersController.validateSession(null));
    assertThrows(ResponseStatusException.class, () -> OrdersController.validateKey("bad"));
  }
}
