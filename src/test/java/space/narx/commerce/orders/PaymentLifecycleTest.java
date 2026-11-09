package space.narx.commerce.orders;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static space.narx.commerce.orders.TestData.*;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

class PaymentLifecycleTest {

  JdbcTemplate db;
  RazorpayClient provider;
  PaymentsController payments;
  Map<String, Object> row;

  @BeforeEach
  void setup() {
    db = database();
    row = order();
    provider = spy(new RazorpayClient(JSON, paymentConfiguration()));
    payments = new PaymentsController(db, administrator(), provider, transaction(), JSON);
    when(db.queryForList(startsWith("SELECT * FROM orders"), any(Object[].class))).thenReturn(
      List.of(row)
    );
    when(db.queryForMap(startsWith("SELECT * FROM orders"), any(Object[].class))).thenReturn(row);
    doNothing().when(provider).verifyCheckout(anyString(), anyString(), anyString());
    doNothing().when(provider).verifyWebhook(any(byte[].class), any());
    doReturn(
      JSON.valueToTree(
        Map.of("id", "order_test", "amount", 100, "currency", "INR", "receipt", ID.toString())
      )
    )
      .when(provider)
      .post(eq("/orders"), any());
    doReturn(payment(100, "captured")).when(provider).get("/payments/pay_test");
  }

  JsonNode payment(int amount, String status) {
    return JSON.valueToTree(
      Map.of(
        "id",
        "pay_test",
        "order_id",
        "order_test",
        "amount",
        amount,
        "currency",
        "INR",
        "status",
        status,
        "captured",
        status.equals("captured")
      )
    );
  }

  PaymentsController.Verification proof() {
    return new PaymentsController.Verification("pay_test", "order_test", "test-proof");
  }

  byte[] webhook(String type, String entity, String id) {
    return JSON.writeValueAsBytes(
      Map.of("event", type, "payload", Map.of(entity, Map.of("entity", Map.of("id", id))))
    );
  }

  @Test
  void checkoutReusesProviderOrderAndRefusesUncertainInitialization() {
    assertEquals(true, ((Map<?, ?>) payments.config()).get("enabled"));
    assertEquals("order_test", ((Map<?, ?>) payments.begin(ID, TOKEN)).get("providerOrderId"));
    row.put("provider_order_id", "order_test");
    payments.begin(ID, TOKEN);
    verify(provider, times(1)).post(eq("/orders"), any());
    row.remove("provider_order_id");
    when(
      db.update(startsWith("UPDATE orders SET payment_status='initializing'"), any(Object[].class))
    ).thenReturn(0);
    assertEquals(
      "payment_initialization_needs_reconciliation",
      assertThrows(ResponseStatusException.class, () -> payments.begin(ID, TOKEN)).getReason()
    );
    row.put("status", "cancelled");
    assertThrows(ResponseStatusException.class, () -> payments.begin(ID, TOKEN));
  }

  @Test
  void providerFailurePersistsUnknownStateRatherThanSilentlyRetrying() {
    doThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "provider down"))
      .when(provider)
      .post(eq("/orders"), any());
    assertThrows(ResponseStatusException.class, () -> payments.begin(ID, TOKEN));
    verify(db).update(
      startsWith("UPDATE orders SET payment_status='initialization_unknown'"),
      eq(ID)
    );
    verify(provider, times(1)).post(eq("/orders"), any());
  }

  @Test
  void captureVerifiesIdentityThenWritesPaymentAndOutboxTogether() {
    row.put("provider_order_id", "order_test");
    payments.verify(ID, proof(), TOKEN);
    verify(provider).verifyCheckout("order_test", "pay_test", "test-proof");
    verify(db).update(startsWith("UPDATE orders SET provider_payment_id"), eq("pay_test"), eq(ID));
    var payload = ArgumentCaptor.forClass(Object.class);
    verify(db).update(startsWith("INSERT INTO order_outbox"), any(), eq(ID), payload.capture());
    var event = JSON.readValue(payload.getValue().toString(), PaidOrderEvent.class);
    assertEquals(ID, event.orderId());
    assertEquals("pay_test", event.paymentId());
    assertEquals(100, event.amount());
    row.put("payment_status", "paid");
    clearInvocations(db);
    payments.verify(ID, proof(), TOKEN);
    verify(db, never()).update(startsWith("INSERT INTO order_outbox"), any(Object[].class));
  }

  @Test
  void captureRejectsWrongOwnerOrderAmountAndNonCapturedPayment() {
    assertThrows(ResponseStatusException.class, () -> payments.verify(ID, proof(), TOKEN));
    row.put("provider_order_id", "other");
    assertThrows(ResponseStatusException.class, () -> payments.verify(ID, proof(), TOKEN));
    row.put("provider_order_id", "order_test");
    doReturn(payment(101, "captured")).when(provider).get("/payments/pay_test");
    assertThrows(ResponseStatusException.class, () -> payments.verify(ID, proof(), TOKEN));
    doReturn(payment(100, "authorized")).when(provider).get("/payments/pay_test");
    assertThrows(ResponseStatusException.class, () -> payments.verify(ID, proof(), TOKEN));
    when(
      db.queryForList(
        startsWith("SELECT * FROM orders WHERE id=? AND customer_id"),
        any(Object[].class)
      )
    ).thenReturn(List.of());
    assertEquals(
      HttpStatus.NOT_FOUND,
      assertThrows(ResponseStatusException.class, () -> payments.begin(ID, TOKEN)).getStatusCode()
    );
    verify(db, never()).update(startsWith("INSERT INTO order_outbox"), any(Object[].class));
  }

  @Test
  void localFixtureCaptureRequiresExplicitFixtureModeAndPayableState() {
    row.put("provider_order_id", "order_test");
    row.put("payment_status", "awaiting_payment");
    doReturn(JSON.valueToTree(Map.of("proof", "fixture")))
      .when(provider)
      .fixtureCapture("order_test");
    assertNotNull(payments.fixtureCapture(ID, TOKEN));
    row.put("payment_status", "paid");
    assertThrows(ResponseStatusException.class, () -> payments.fixtureCapture(ID, TOKEN));
    doReturn(false).when(provider).fixtureEnabled();
    assertEquals(
      HttpStatus.NOT_FOUND,
      assertThrows(ResponseStatusException.class, () ->
        payments.fixtureCapture(ID, TOKEN)
      ).getStatusCode()
    );
  }

  @Test
  void webhookDeduplicatesAfterSignatureAndRejectsMalformedInputs() {
    row.put("provider_order_id", "order_test");
    byte[] body = webhook("payment.captured", "payment", "pay_test");
    assertEquals(
      "received",
      ((Map<?, ?>) payments.webhook(body, "signature", "event-1")).get("status")
    );
    verify(provider).verifyWebhook(body, "signature");
    verify(db).update(startsWith("INSERT INTO payment_events"), eq("event-1"));
    when(db.queryForList(startsWith("SELECT event_id"), any(Object[].class))).thenReturn(
      List.of(Map.of("event_id", "event-1"))
    );
    assertEquals(
      "already_received",
      ((Map<?, ?>) payments.webhook(body, "signature", "event-1")).get("status")
    );
    when(db.queryForList(startsWith("SELECT event_id"), any(Object[].class))).thenReturn(List.of());
    assertThrows(ResponseStatusException.class, () ->
      payments.webhook(body, "signature", "../bad")
    );
    assertThrows(ResponseStatusException.class, () ->
      payments.webhook(new byte[262145], "signature", "event-2")
    );
    assertEquals(
      "invalid_json",
      assertThrows(ResponseStatusException.class, () ->
        payments.webhook("bad".getBytes(StandardCharsets.UTF_8), "signature", "event-2")
      ).getReason()
    );
    assertEquals(
      "received",
      ((Map<?, ?>) payments.webhook(
          webhook("ignored", "other", "none"),
          "signature",
          "event-3"
        )).get("status")
    );
  }

  @Test
  void reconciliationValidatesRemoteReceiptBeforeAttachingAnUncertainOrder() {
    doReturn(
      JSON.valueToTree(
        Map.of("id", "order_test", "amount", 100, "currency", "INR", "receipt", ID.toString())
      )
    )
      .when(provider)
      .get("/orders/order_test");
    assertEquals(
      "reconciled",
      ((Map<?, ?>) payments.reconcile(
          ID,
          new PaymentsController.Reconcile("order_test"),
          TOKEN
        )).get("status")
    );
    when(
      db.update(startsWith("UPDATE orders SET provider_order_id"), any(Object[].class))
    ).thenReturn(0);
    assertEquals(
      "order_not_reconcilable",
      assertThrows(ResponseStatusException.class, () ->
        payments.reconcile(ID, new PaymentsController.Reconcile("order_test"), TOKEN)
      ).getReason()
    );
    doReturn(JSON.valueToTree(Map.of("amount", 999, "currency", "INR", "receipt", ID.toString())))
      .when(provider)
      .get("/orders/order_test");
    assertEquals(
      "payment_identity_mismatch",
      assertThrows(ResponseStatusException.class, () ->
        payments.reconcile(ID, new PaymentsController.Reconcile("order_test"), TOKEN)
      ).getReason()
    );
  }

  @Test
  void refundPersistsAnIdempotencyKeyAndRestocksOnlyBeforeShipment() {
    row.put("status", "confirmed");
    row.put("payment_status", "paid");
    row.put("provider_payment_id", "pay_test");
    doAnswer(call -> {
      row.put("payment_status", "refunding");
      row.put("refund_key", call.getArgument(1));
      return 1;
    })
      .when(db)
      .update(startsWith("UPDATE orders SET payment_status='refunding'"), any(Object[].class));
    doAnswer(call -> {
      row.put("refund_id", call.getArgument(1));
      return 1;
    })
      .when(db)
      .update(startsWith("UPDATE orders SET refund_id"), any(Object[].class));
    var refund = JSON.valueToTree(
      Map.of("id", "rfnd_test", "payment_id", "pay_test", "amount", 100, "status", "processed")
    );
    doReturn(refund)
      .when(provider)
      .request(eq("POST"), eq("/payments/pay_test/refund"), any(), any());
    assertEquals(
      "processed",
      ((Map<?, ?>) payments.refund(
          ID,
          new PaymentsController.Refund("Customer request"),
          TOKEN
        )).get("status")
    );
    verify(provider).request(
      eq("POST"),
      eq("/payments/pay_test/refund"),
      any(),
      eq(row.get("refund_key").toString())
    );
    verify(db).update(startsWith("UPDATE products SET stock=stock+"), eq(1), eq("mug"));
    row.put("payment_status", "refunded");
    clearInvocations(provider);
    assertEquals(
      "refunded",
      ((Map<?, ?>) payments.refund(ID, new PaymentsController.Refund("Retry"), TOKEN)).get("status")
    );
    verify(provider, never()).request(any(), any(), any(), any());
  }

  @Test
  void refundWebhookHandlesPendingFailedAndRepeatedResultsSafely() {
    row.put("provider_payment_id", "pay_test");
    row.put("refund_id", "rfnd_test");
    row.put("payment_status", "refunding");
    row.put("status", "shipped");
    for (String status : List.of("pending", "failed", "processed")) {
      doReturn(
        JSON.valueToTree(
          Map.of("id", "rfnd_test", "payment_id", "pay_test", "amount", 100, "status", status)
        )
      )
        .when(provider)
        .get("/refunds/rfnd_test");
      assertEquals(
        "received",
        ((Map<?, ?>) payments.webhook(
            webhook("refund.processed", "refund", "rfnd_test"),
            "signature",
            "refund-" + status
          )).get("status")
      );
    }
    verify(db, never()).update(startsWith("UPDATE products SET stock=stock+"), any(Object[].class));
    row.put("payment_status", "unpaid");
    assertThrows(ResponseStatusException.class, () ->
      payments.refund(ID, new PaymentsController.Refund("Request"), TOKEN)
    );
    assertThrows(ResponseStatusException.class, () ->
      payments.refund(ID, new PaymentsController.Refund(""), TOKEN)
    );
    row.put("items", "invalid-json");
    assertThrows(IllegalStateException.class, () -> payments.restock(row));
  }
}
