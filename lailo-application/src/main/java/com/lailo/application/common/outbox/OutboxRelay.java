package com.lailo.application.common.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Polls the outbox and publishes to Kafka. Key = aggregate id, so all events of one user stay ordered
 * in one partition. A production alternative is CDC (Debezium) tailing the outbox table.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "lailo.outbox", name = "relay-enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelay {

    private static final int BATCH_SIZE = 100;
    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(10);

    private final OutboxRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final TransactionalOperator tx;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Scheduled(fixedDelayString = "${lailo.outbox.poll-interval:1s}")
    public void relay() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            Long sent = relayBatch().block(Duration.ofSeconds(60));
            if (sent != null && sent > 0) {
                log.debug("Relayed {} outbox message(s)", sent);
            }
        } catch (Exception ex) {
            log.warn("Outbox relay cycle failed: {}", ex.getMessage());
        } finally {
            running.set(false);
        }
    }

    Mono<Long> relayBatch() {
        return repository.lockUnpublished(BATCH_SIZE)
                .concatMap(this::publish)
                .count()
                .as(tx::transactional);
    }

    private Mono<Boolean> publish(OutboxMessage msg) {
        ProducerRecord<String, String> record = new ProducerRecord<>(msg.getTopic(), msg.getAggregateId(), msg.getPayload());
        record.headers().add("eventType", msg.getEventType().getBytes(StandardCharsets.UTF_8));
        record.headers().add("eventId", msg.getId().toString().getBytes(StandardCharsets.UTF_8));
        return Mono.fromFuture(() -> kafkaTemplate.send(record))
                .timeout(SEND_TIMEOUT)
                .then(repository.markPublished(msg.getId(), Instant.now(clock)))
                .thenReturn(true)
                .onErrorResume(ex -> {
                    log.warn("Failed to publish outbox message {} ({}): {}", msg.getId(), msg.getEventType(), ex.getMessage());
                    return repository.markFailed(msg.getId()).thenReturn(false);
                });
    }
}
