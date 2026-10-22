package dev.boutique.orders;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
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

@Component
@ConditionalOnProperty(name = "delivery.role", havingValue = "publisher")
class OutboxPublisher {
    private static final Logger LOG = LoggerFactory.getLogger(OutboxPublisher.class);
    private final JdbcTemplate db;
    private final SqsClient queue;
    private final String queueUrl;
    private final TransactionTemplate tx;
    private final MeterRegistry metrics;
    private final AtomicLong pending = new AtomicLong();
    private final AtomicLong oldest = new AtomicLong();

    OutboxPublisher(JdbcTemplate db, SqsClient queue, @Value("${delivery.queue-url}") String queueUrl,
                    PlatformTransactionManager manager, MeterRegistry metrics) {
        this.db = db; this.queue = queue; this.queueUrl = queueUrl; this.metrics = metrics;
        tx = new TransactionTemplate(manager);
        tx.setTimeout(20);
        metrics.gauge("commerce.outbox.pending", pending);
        metrics.gauge("commerce.outbox.oldest.seconds", oldest);
    }

    @Scheduled(fixedDelay = 30000, initialDelay = 1000)
    void measureBacklog() {
        var row = db.queryForMap("""
            SELECT COUNT(*) AS pending,
                COALESCE(EXTRACT(EPOCH FROM CURRENT_TIMESTAMP-MIN(created_at)),0) AS oldest
            FROM order_outbox WHERE published_at IS NULL
            """);
        pending.set(((Number) row.get("pending")).longValue());
        oldest.set(((Number) row.get("oldest")).longValue());
    }

    @Scheduled(fixedDelay = 500, initialDelay = 1000)
    void publish() {
        // One bounded network call under a row lock; SKIP LOCKED permits other replicas.
        tx.executeWithoutResult(status -> {
            var rows = db.queryForList("""
                SELECT id,payload,attempts FROM order_outbox
                WHERE published_at IS NULL AND next_attempt_at<=CURRENT_TIMESTAMP
                ORDER BY next_attempt_at,created_at LIMIT 1 FOR NO KEY UPDATE SKIP LOCKED
                """);
            if (rows.isEmpty()) return;
            var row = rows.getFirst();
            try {
                queue.sendMessage(r -> r.queueUrl(queueUrl).messageBody(row.get("payload").toString()));
            } catch (RuntimeException failure) {
                int attempts = ((Number) row.get("attempts")).intValue();
                int delay = Math.min(60, 1 << Math.min(attempts + 1, 6));
                db.update("""
                    UPDATE order_outbox SET attempts=attempts+1,
                        next_attempt_at=CURRENT_TIMESTAMP+(? * INTERVAL '1 second') WHERE id=?
                    """, delay, row.get("id"));
                metrics.counter("commerce.outbox.publish", "result", "failed").increment();
                LOG.warn("outbox_publish_failed event_id={} retry_seconds={} error_type={}",
                    row.get("id"), delay, failure.getClass().getSimpleName());
                return;
            }
            // A crash after SendMessage but before this commit intentionally causes a duplicate.
            db.update("UPDATE order_outbox SET published_at=CURRENT_TIMESTAMP,attempts=attempts+1 WHERE id=?", row.get("id"));
            metrics.counter("commerce.outbox.publish", "result", "sent").increment();
            LOG.info("outbox_published event_id={}", row.get("id"));
        });
    }
}
