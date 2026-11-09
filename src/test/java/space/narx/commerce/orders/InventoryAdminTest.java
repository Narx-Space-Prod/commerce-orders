package space.narx.commerce.orders;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static space.narx.commerce.orders.TestData.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class InventoryAdminTest {

  @Test
  void inventoryWritesRequireAdminAndProtectOptimisticVersions() {
    var db = database();
    var accounts = administrator();
    var controller = new CatalogueController(db, accounts);
    when(db.queryForObject(anyString(), eq(String.class))).thenReturn("INR");
    when(db.queryForMap(startsWith("SELECT * FROM products"), any(Object[].class))).thenReturn(
      Map.of("id", "mug")
    );
    controller.products();
    controller.all(TOKEN);
    assertEquals(Map.of("id", "mug"), controller.save("mug", product(0), TOKEN));
    verify(db).update(
      startsWith("INSERT INTO products"),
      eq("mug"),
      eq("Mug"),
      eq("Stoneware"),
      eq("◈"),
      eq(100),
      eq("INR"),
      eq(8),
      eq(true)
    );
    when(db.queryForList(startsWith("SELECT version,currency"), any(Object[].class))).thenReturn(
      List.of(Map.of("version", 2, "currency", "INR"))
    );
    assertThrows(ResponseStatusException.class, () -> controller.save("mug", product(1), TOKEN));
    controller.save("mug", product(2), TOKEN);
    verify(db).update(startsWith("UPDATE products SET name"), any(Object[].class));
    assertThrows(ResponseStatusException.class, () -> controller.save("other", product(0), TOKEN));
    verify(accounts, atLeastOnce()).admin(TOKEN);
    doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN))
      .when(accounts)
      .admin("customer");
    assertThrows(ResponseStatusException.class, () -> controller.all("customer"));
  }

  @Test
  void dashboardAndOrderHistoryRequireAuthorizationAndBoundPagination() {
    var db = database();
    var accounts = administrator();
    var controller = new AdminController(db, accounts, JSON, mock(PaymentsController.class));
    when(db.queryForMap(anyString())).thenReturn(Map.of("orders", 1));
    assertTrue(((Map<?, ?>) controller.dashboard(TOKEN)).containsKey("sales"));
    controller.audit(TOKEN);
    var rows = new ArrayList<Map<String, Object>>();
    for (int i = 0; i < 21; i++) rows.add(order());
    when(db.queryForList(contains("FROM orders o LEFT JOIN"), any(Object[].class))).thenReturn(
      rows
    );
    var page = (Map<?, ?>) controller.orders(TOKEN, 1);
    assertEquals(true, page.get("hasMore"));
    assertEquals(20, ((List<?>) page.get("orders")).size());
    assertThrows(ResponseStatusException.class, () -> controller.orders(TOKEN, -1));
    verify(accounts, atLeastOnce()).admin(TOKEN);
  }

  @Test
  void fulfilmentTransitionsAreSequentialAndUnpaidCancellationRestocks() {
    var db = database();
    var payments = mock(PaymentsController.class);
    var controller = new AdminController(db, administrator(), JSON, payments);
    var row = order();
    when(db.queryForList(startsWith("SELECT * FROM orders"), any(Object[].class))).thenReturn(
      List.of(row)
    );
    assertEquals(
      "cancelled",
      ((Map<?, ?>) controller.update(ID, new AdminController.Status("cancelled"), TOKEN)).get(
        "status"
      )
    );
    verify(payments).restock(row);
    for (var entry : Map.of(
      "confirmed",
      "processing",
      "processing",
      "shipped",
      "shipped",
      "delivered"
    ).entrySet()) {
      row.put("status", entry.getKey());
      row.put("payment_status", "paid");
      assertEquals(
        entry.getValue(),
        ((Map<?, ?>) controller.update(
            ID,
            new AdminController.Status(entry.getValue()),
            TOKEN
          )).get("status")
      );
    }
    assertThrows(ResponseStatusException.class, () ->
      controller.update(ID, new AdminController.Status("confirmed"), TOKEN)
    );
    when(db.queryForList(startsWith("SELECT * FROM orders"), any(Object[].class))).thenReturn(
      List.of()
    );
    assertThrows(ResponseStatusException.class, () ->
      controller.update(ID, new AdminController.Status("cancelled"), TOKEN)
    );
  }
}
