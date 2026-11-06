package com.lailo.application.common.outbox;

import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

public interface OutboxRepository extends ReactiveCrudRepository<OutboxMessage, UUID> {

    /** SKIP LOCKED lets several app instances relay concurrently without double-sending a row. */
    @Query("""
            SELECT * FROM outbox_messages
            WHERE published_at IS NULL
            ORDER BY created_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """)
    Flux<OutboxMessage> lockUnpublished(int limit);

    @Modifying
    @Query("UPDATE outbox_messages SET published_at = :at, version = version + 1 WHERE id = :id")
    Mono<Integer> markPublished(UUID id, Instant at);

    @Modifying
    @Query("UPDATE outbox_messages SET attempts = attempts + 1, version = version + 1 WHERE id = :id")
    Mono<Integer> markFailed(UUID id);
}
