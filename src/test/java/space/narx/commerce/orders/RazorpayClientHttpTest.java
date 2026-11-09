package space.narx.commerce.orders;

import static org.junit.jupiter.api.Assertions.*;
import static space.narx.commerce.orders.TestData.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Real HTTP/crypto against loopback only; no external provider or live keys. */
class RazorpayClientHttpTest {

  HttpServer server;
  RazorpayClient client;
  AtomicInteger status = new AtomicInteger(200);
  AtomicReference<String> response = new AtomicReference<>("{\"id\":\"order_test\"}");
  AtomicReference<String> method = new AtomicReference<>(),
    path = new AtomicReference<>(),
    authorization = new AtomicReference<>(),
    refundKey = new AtomicReference<>(),
    body = new AtomicReference<>();

  @BeforeEach
  void setup() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      method.set(exchange.getRequestMethod());
      path.set(exchange.getRequestURI().getPath());
      authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
      refundKey.set(exchange.getRequestHeaders().getFirst("X-Refund-Idempotency"));
      body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      byte[] payload = response.get().getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(status.get(), payload.length);
      exchange.getResponseBody().write(payload);
      exchange.close();
    });
    server.start();
    var configuration = new HashMap<>(paymentConfiguration());
    configuration.put(
      "RAZORPAY_API_BASE",
      "http://127.0.0.1:" + server.getAddress().getPort() + "/v1"
    );
    client = new RazorpayClient(JSON, configuration);
  }

  @AfterEach
  void stop() {
    Thread.interrupted();
    server.stop(0);
  }

  @Test
  void providerRequestsUseBasicAuthAndStableRefundIdempotency() {
    assertEquals("order_test", client.post("/orders", Map.of("amount", 100)).path("id").asText());
    assertEquals("POST", method.get());
    assertEquals("/v1/orders", path.get());
    assertTrue(authorization.get().startsWith("Basic "));
    assertEquals(100, JSON.readTree(body.get()).path("amount").asInt());
    client.get("/payments/pay_test");
    assertEquals("GET", method.get());
    assertEquals("", body.get());
    client.request("POST", "/payments/pay_test/refund", Map.of("amount", 100), "refund-attempt-1");
    assertEquals("refund-attempt-1", refundKey.get());
    client.fixtureCapture("order_test");
    assertEquals("/_capture/order_test", path.get());
  }

  @Test
  void unconfiguredLiveAndUnapprovedProviderConfigurationIsRejected() {
    var disabled = new RazorpayClient(JSON, Map.of());
    assertFalse(disabled.enabled());
    assertFalse(disabled.fixtureEnabled());
    assertThrows(ResponseStatusException.class, disabled::requireEnabled);
    assertThrows(ResponseStatusException.class, () -> disabled.fixtureCapture("order_test"));
    assertNotNull(new RazorpayClient(JSON));
    assertThrows(IllegalStateException.class, () ->
      new RazorpayClient(JSON, Map.of("RAZORPAY_KEY_ID", "rzp_live_rejected"))
    );
    assertThrows(IllegalStateException.class, () ->
      new RazorpayClient(JSON, Map.of("RAZORPAY_API_BASE", "https://example.invalid/v1"))
    );
    assertThrows(IllegalStateException.class, () ->
      new RazorpayClient(
        JSON,
        Map.of("RAZORPAY_KEY_ID", "rzp_test_fixture", "RAZORPAY_API_BASE", "http://localhost/v1")
      )
    );
  }

  @Test
  void providerErrorsAndMalformedBodiesNeverExposeResponseDetails() {
    status.set(500);
    response.set("private-provider-details");
    var rejected = assertThrows(ResponseStatusException.class, () ->
      client.get("/orders/order_test")
    );
    assertEquals(HttpStatus.BAD_GATEWAY, rejected.getStatusCode());
    assertFalse(rejected.getMessage().contains("private-provider-details"));
    assertThrows(ResponseStatusException.class, () -> client.fixtureCapture("order_test"));
    status.set(200);
    response.set("not-json");
    assertEquals(
      HttpStatus.SERVICE_UNAVAILABLE,
      assertThrows(ResponseStatusException.class, () ->
        client.get("/orders/order_test")
      ).getStatusCode()
    );
    assertThrows(ResponseStatusException.class, () -> client.fixtureCapture("order_test"));
  }

  @Test
  void signaturesBindExactCheckoutAndWebhookPayloads() throws Exception {
    client.verifyCheckout(
      "order_test",
      "pay_test",
      signature("order_test|pay_test", "unit-test-secret")
    );
    byte[] raw = "{\"event\":\"payment.captured\"}".getBytes(StandardCharsets.UTF_8);
    client.verifyWebhook(
      raw,
      signature(new String(raw, StandardCharsets.UTF_8), "unit-webhook-secret")
    );
    assertThrows(ResponseStatusException.class, () ->
      client.verifyCheckout(
        "other",
        "pay_test",
        signature("order_test|pay_test", "unit-test-secret")
      )
    );
    assertThrows(ResponseStatusException.class, () -> RazorpayClient.identifier(null, "pay_"));
  }

  @Test
  void interruptedProviderCallsPreserveInterruptStatus() {
    Thread.currentThread().interrupt();
    assertThrows(ResponseStatusException.class, () -> client.get("/orders/order_test"));
    assertTrue(Thread.interrupted());
    Thread.currentThread().interrupt();
    assertThrows(ResponseStatusException.class, () -> client.fixtureCapture("order_test"));
    assertTrue(Thread.interrupted());
  }

  static String signature(String data, String key) throws Exception {
    var mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
  }
}
