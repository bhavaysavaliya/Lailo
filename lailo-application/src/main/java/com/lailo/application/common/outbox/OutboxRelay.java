package com.lailo.application.common.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

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

    private static final int BATCH_SIZE = 25;
    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(10);
    /**
     * Longest one cycle may take. Deliberately only a little above {@code BATCH_SIZE * SEND_TIMEOUT} worth
     * of headroom: a cycle that overruns is abandoned, and the sooner that happens the sooner the next cycle
     * picks the same rows up again (they are still unpublished, and publishing is idempotent downstream).
     */
    private static final Duration CYCLE_BUDGET = Duration.ofSeconds(20);

    private final OutboxRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final TransactionalOperator tx;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * Scheduled entry point: relays one batch per tick. Skips the tick if the previous one is still running
     * and never lets an exception kill the scheduler thread.
     */
    @Scheduled(fixedDelayString = "${lailo.outbox.poll-interval:1s}")
    public void relay() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            Long sent = relayBatch().block(CYCLE_BUDGET);
            if (sent != null && sent > 0) {
                log.debug("Relayed {} outbox message(s)", sent);
            }
        } catch (Exception ex) {
            log.warn("Outbox relay cycle failed: {}", ex.getMessage());
        } finally {
            running.set(false);
        }
    }

    /**
     * Locks up to {@code BATCH_SIZE} pending rows (skipping rows locked by other instances), publishes them in
     * order and marks each one, all inside one transaction.
     *
     * @return the number of rows processed in this batch
     */
    Mono<Long> relayBatch() {
        return repository.lockUnpublished(BATCH_SIZE)
                // Drain the locked rows into memory before publishing any of them. Publishing straight off
                // the cursor leaves that query's result stream open while `markPublished` tries to run on
                // the same connection, and a single R2DBC connection cannot interleave two statements: the
                // connection goes idle-in-transaction mid-cursor and the batch never finishes. The row locks
                // are held by the transaction either way, so nothing is lost by reading them first.
                .collectList()
                .flatMapMany(Flux::fromIterable)
                .concatMap(this::publish)
                .count()
                .as(tx::transactional);
    }

    /**
     * Publishes one outbox row to Kafka and records the outcome. A failure increments {@code attempts} and
     * leaves the row pending for the next tick instead of failing the batch.
     *
     * @param msg the outbox row to publish
     * @return {@code true} if Kafka acknowledged the record, {@code false} if it will be retried
     */
    private Mono<Boolean> publish(OutboxMessage msg) {
        ProducerRecord<String, String> record = new ProducerRecord<>(msg.getTopic(), msg.getAggregateId(), msg.getPayload());
        record.headers().add("eventType", msg.getEventType().getBytes(StandardCharsets.UTF_8));
        record.headers().add("eventId", msg.getId().toString().getBytes(StandardCharsets.UTF_8));
        return Mono.fromFuture(() -> kafkaTemplate.send(record))
                // KafkaTemplate.send() is not as non-blocking as its Future return type suggests: it blocks
                // the calling thread while the producer waits for topic metadata (up to max.block.ms). This
                // chain is driven by the R2DBC client's event loop, so blocking on it stops that loop from
                // delivering the database responses this very transaction is waiting for - a deadlock that
                // stalls the relay and every connection sharing the loop. Subscribing on boundedElastic
                // keeps the blocking part off the event loop; the timeout then has something to interrupt.
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(SEND_TIMEOUT)
                .then(repository.markPublished(msg.getId(), Instant.now(clock)))
                .thenReturn(true)
                .onErrorResume(ex -> {
                    log.warn("Failed to publish outbox message {} ({}): {}", msg.getId(), msg.getEventType(), ex.getMessage());
                    return repository.markFailed(msg.getId()).thenReturn(false);
                });
    }
}
