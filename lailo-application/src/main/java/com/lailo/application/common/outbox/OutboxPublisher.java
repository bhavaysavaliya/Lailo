package com.lailo.application.common.outbox;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/** Appends an event to the outbox. Call inside the transaction that changes the aggregate. */
@Component
@RequiredArgsConstructor
public class OutboxPublisher {

    private final OutboxRepository repository;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    /**
     * Serialises an event to JSON and inserts it as a pending outbox row. Must be composed into the same
     * transaction as the state change it describes.
     *
     * @param topic Kafka topic the relay will publish to
     * @param aggregateType aggregate name, e.g. {@code User}
     * @param aggregateId aggregate id; becomes the Kafka record key, which keeps per-aggregate ordering
     * @param eventType event name, sent as the {@code eventType} header
     * @param event the event payload (a record)
     * @return the saved outbox row
     */
    public Mono<OutboxMessage> append(String topic, String aggregateType, String aggregateId,
                                      String eventType, Object event) {
        return Mono.fromCallable(() -> jsonMapper.writeValueAsString(event))
                .flatMap(json -> repository.save(OutboxMessage.builder()
                        .id(UUID.randomUUID())
                        .aggregateType(aggregateType)
                        .aggregateId(aggregateId)
                        .eventType(eventType)
                        .topic(topic)
                        .payload(json)
                        .createdAt(Instant.now(clock))
                        .build()));
    }
}
