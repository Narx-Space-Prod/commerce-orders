package dev.boutique.orders;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.sqs.SqsClient;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(name = "delivery.role", havingValue = "worker")
class ReceiptWorker {
    private static final Logger LOG = LoggerFactory.getLogger(ReceiptWorker.class);
    private final JdbcTemplate db;
    private final ObjectMapper mapper;
    private final SqsClient queue;
    private final String queueUrl;
    private final TransactionTemplate tx;
    private final MeterRegistry metrics;
    private final int visibility;

    ReceiptWorker(JdbcTemplate db, ObjectMapper mapper, SqsClient queue,
                  @Value("${delivery.queue-url}") String queueUrl,
                  PlatformTransactionManager manager, MeterRegistry metrics,
                  @Value("${delivery.visibility-seconds}") int visibility) {
        this.db = db; this.mapper = mapper; this.queue = queue; this.queueUrl = queueUrl; this.metrics = metrics;
        if (visibility < 15 || visibility > 43200) throw new IllegalArgumentException("visibility_must_exceed_processing_timeout");
        this.visibility = visibility;
        tx = new TransactionTemplate(manager);
        tx.setTimeout(10);
    }

    @Scheduled(fixedDelay = 500, initialDelay = 1000)
    void poll() {
        try {
            var messages = queue.receiveMessage(r -> r.queueUrl(queueUrl).maxNumberOfMessages(1)
                .waitTimeSeconds(10).visibilityTimeout(visibility)).messages();
            for (var message : messages) {
                try {
                    process(message.body());
                    // Only acknowledge after the receipt AND inbox marker have committed.
                    queue.deleteMessage(r -> r.queueUrl(queueUrl).receiptHandle(message.receiptHandle()));
                } catch (RuntimeException failure) {
                    metrics.counter("commerce.queue.consume", "result", "failed").increment();
                    LOG.warn("receipt_processing_failed message_id={} error_type={} retry_after_visibility_timeout=true",
                        message.messageId(), failure.getClass().getSimpleName());
                    // No delete. SQS retries and applies the queue's DLQ redrive policy.
                }
            }
        } catch (RuntimeException failure) {
            metrics.counter("commerce.queue.poll", "result", "failed").increment();
            LOG.warn("receipt_queue_poll_failed error_type={}", failure.getClass().getSimpleName());
        }
    }

    void process(String body) {
        if (body == null || body.length() > 16384) throw new IllegalArgumentException("invalid_event_size");
        PaidOrderEvent event = mapper.readValue(body, PaidOrderEvent.class);
        event.validate();
        boolean created = Boolean.TRUE.equals(tx.execute(status -> {
            // Canonical committed event validation also rejects tampered/unrelated queue messages.
            var rows = db.queryForList("SELECT payload FROM order_outbox WHERE id=?", event.eventId());
            if (rows.isEmpty() || !event.equals(mapper.readValue(rows.getFirst().get("payload").toString(), PaidOrderEvent.class))) {
                throw new IllegalArgumentException("unknown_or_mismatched_event");
            }
            int inserted = db.update("INSERT INTO processed_order_events(event_id) VALUES (?) ON CONFLICT DO NOTHING", event.eventId());
            if (inserted == 0) return false;
            db.update("""
                INSERT INTO payment_receipts(id,order_id,event_id,amount,currency,payment_id)
                VALUES (?,?,?,?,?,?)
                """, UUID.randomUUID(), event.orderId(), event.eventId(), event.amount(), event.currency(), event.paymentId());
            return true;
        }));
        metrics.counter("commerce.queue.consume", "result", created ? "processed" : "duplicate").increment();
        LOG.info("receipt_event_handled event_id={} order_id={} duplicate={}", event.eventId(), event.orderId(), !created);
    }
}
