package space.narx.commerce.orders;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static space.narx.commerce.orders.TestData.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;

class DeliveryWorkerTest {

  @Test
  void publisherMeasuresBacklogAndMarksOnlySuccessfullySentEvents() {
    var db = database();
    var queue = mock(SqsClient.class);
    var meters = new SimpleMeterRegistry();
    when(db.queryForMap(anyString())).thenReturn(Map.of("pending", 2, "oldest", 125));
    var publisher = new OutboxPublisher(db, queue, "queue", manager(), meters);
    publisher.measureBacklog();
    assertEquals(2, meters.get("commerce.outbox.pending").gauge().value());
    assertEquals(125, meters.get("commerce.outbox.oldest.seconds").gauge().value());
    publisher.publish();
    verify(queue, never()).sendMessage(any(Consumer.class));
    when(db.queryForList(contains("FROM order_outbox"))).thenReturn(
      List.of(Map.of("id", ID, "payload", "event-json", "attempts", 0))
    );
    publisher.publish();
    verify(queue).sendMessage(any(Consumer.class));
    verify(db).update(startsWith("UPDATE order_outbox SET published_at"), eq(ID));
    assertEquals(1, meters.get("commerce.outbox.publish").tag("result", "sent").counter().count());
  }

  @Test
  void queueFailureRetainsOutboxAndSchedulesBoundedBackoff() {
    var db = database();
    var queue = mock(SqsClient.class);
    var meters = new SimpleMeterRegistry();
    when(db.queryForList(contains("FROM order_outbox"))).thenReturn(
      List.of(Map.of("id", ID, "payload", "event-json", "attempts", 100))
    );
    doThrow(new IllegalStateException("offline")).when(queue).sendMessage(any(Consumer.class));
    new OutboxPublisher(db, queue, "queue", manager(), meters).publish();
    verify(db, never()).update(
      startsWith("UPDATE order_outbox SET published_at"),
      any(Object[].class)
    );
    verify(db).update(contains("next_attempt_at=CURRENT_TIMESTAMP"), eq(60), eq(ID));
    assertEquals(
      1,
      meters.get("commerce.outbox.publish").tag("result", "failed").counter().count()
    );
  }

  @Test
  void receiptIsWrittenOnceAndAcknowledgedAfterCommit() {
    var db = database();
    var queue = mock(SqsClient.class);
    var tx = manager();
    var meters = new SimpleMeterRegistry();
    var event = new PaidOrderEvent(UUID.randomUUID(), "order.paid", 1, ID, 100, "INR", "pay_test");
    String body = JSON.writeValueAsString(event);
    when(db.queryForList(startsWith("SELECT payload"), any(Object[].class))).thenReturn(
      List.of(Map.of("payload", body))
    );
    when(queue.receiveMessage(any(Consumer.class))).thenReturn(
      ReceiveMessageResponse.builder()
        .messages(Message.builder().messageId("m").receiptHandle("receipt").body(body).build())
        .build()
    );
    var worker = new ReceiptWorker(db, JSON, queue, "queue", tx, meters, 60);
    worker.poll();
    var ordered = inOrder(tx, db, queue);
    ordered.verify(tx).getTransaction(any());
    ordered
      .verify(db)
      .update(startsWith("INSERT INTO processed_order_events"), eq(event.eventId()));
    ordered.verify(db).update(contains("INSERT INTO payment_receipts"), any(Object[].class));
    ordered.verify(tx).commit(any());
    ordered.verify(queue).deleteMessage(any(Consumer.class));
    assertEquals(
      1,
      meters.get("commerce.queue.consume").tag("result", "processed").counter().count()
    );
    clearInvocations(db);
    when(
      db.update(startsWith("INSERT INTO processed_order_events"), any(Object[].class))
    ).thenReturn(0);
    worker.process(body);
    verify(db, never()).update(contains("INSERT INTO payment_receipts"), any(Object[].class));
    assertEquals(
      1,
      meters.get("commerce.queue.consume").tag("result", "duplicate").counter().count()
    );
  }

  @Test
  void tamperedOrFailedMessagesAreNeverAcknowledgedAndPollingFailureIsObservable() {
    var db = database();
    var queue = mock(SqsClient.class);
    var meters = new SimpleMeterRegistry();
    var event = new PaidOrderEvent(UUID.randomUUID(), "order.paid", 1, ID, 100, "INR", "pay_test");
    String body = JSON.writeValueAsString(event);
    when(queue.receiveMessage(any(Consumer.class))).thenReturn(
      ReceiveMessageResponse.builder()
        .messages(Message.builder().messageId("m").receiptHandle("receipt").body(body).build())
        .build()
    );
    var worker = new ReceiptWorker(db, JSON, queue, "queue", manager(), meters, 60);
    worker.poll();
    verify(queue, never()).deleteMessage(any(Consumer.class));
    assertEquals(1, meters.get("commerce.queue.consume").tag("result", "failed").counter().count());
    assertThrows(IllegalArgumentException.class, () -> worker.process(null));
    assertThrows(IllegalArgumentException.class, () -> worker.process("x".repeat(16385)));
    doThrow(new IllegalStateException("offline")).when(queue).receiveMessage(any(Consumer.class));
    worker.poll();
    assertEquals(1, meters.get("commerce.queue.poll").tag("result", "failed").counter().count());
    assertThrows(IllegalArgumentException.class, () ->
      new ReceiptWorker(db, JSON, queue, "queue", manager(), meters, 1)
    );
    try (
      var local = new DeliveryConfiguration("worker").queueClient(
        "http://localhost:9324/queue",
        "http://localhost:9324",
        true,
        "ap-south-1"
      );
    ) {
      assertNotNull(local);
    }
    try (
      var aws = new DeliveryConfiguration("publisher").queueClient(
        "https://sqs.ap-south-1.amazonaws.com/123/queue",
        "",
        false,
        "ap-south-1"
      );
    ) {
      assertNotNull(aws);
    }
  }
}
