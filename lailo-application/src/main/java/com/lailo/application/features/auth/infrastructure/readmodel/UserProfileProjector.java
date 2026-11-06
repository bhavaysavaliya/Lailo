package com.lailo.application.features.auth.infrastructure.readmodel;

import com.lailo.application.features.auth.domain.event.AuthEvents;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Idempotent upserts: replaying an event (at-least-once delivery) or receiving events out of order
 * leaves the same document ($setOnInsert, $min/$max on timestamps).
 */
@Component
@RequiredArgsConstructor
public class UserProfileProjector {

    private final ReactiveMongoTemplate mongo;

    public Mono<Void> on(AuthEvents.UserRegistered e) {
        Update u = new Update().min("registeredAt", e.occurredAt());
        setIfPresent(u, "email", e.email());
        setIfPresent(u, "phone", e.phone());
        return upsert(e.userId(), u);
    }

    public Mono<Void> on(AuthEvents.UserLoggedIn e) {
        Update u = new Update()
                .max("lastLoginAt", e.occurredAt())
                .min("registeredAt", e.occurredAt())
                .set("lastLoginMethod", e.identifierType());
        setIfPresent(u, "email", e.email());
        setIfPresent(u, "phone", e.phone());
        return upsert(e.userId(), u);
    }

    private static void setIfPresent(Update u, String field, String value) {
        if (value != null) {
            u.set(field, value);
        }
    }

    private Mono<Void> upsert(String userId, Update update) {
        return mongo.findAndModify(Query.query(Criteria.where("_id").is(userId)), update,
                        FindAndModifyOptions.options().upsert(true), UserProfileDocument.class)
                .then();
    }
}
