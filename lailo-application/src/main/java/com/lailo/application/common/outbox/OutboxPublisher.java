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
