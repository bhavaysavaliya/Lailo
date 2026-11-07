package com.lailo.application.features.auth.infrastructure.messaging;

import com.lailo.application.features.auth.domain.event.AuthEvents;
import com.lailo.application.features.auth.infrastructure.readmodel.UserProfileProjector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** Keeps the MongoDB read model in sync with the Postgres write model. */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserEventsListener {

    private final JsonMapper jsonMapper;
    private final UserProfileProjector projector;

    /**
     * Routes a record by its {@code eventType} header to the projector and waits for the Mongo write. Unknown
     * types are ignored; failures are retried by the listener container.
     *
     * @param record the Kafka record (key = user id, value = event JSON)
     */
    @KafkaListener(topics = AuthEvents.TOPIC, groupId = "lailo-user-profile-projector")
    public void onMessage(ConsumerRecord<String, String> record) {
        var header = record.headers().lastHeader("eventType");
        String type = header == null ? "" : new String(header.value(), StandardCharsets.UTF_8);
        Mono<Void> work = switch (type) {
            case AuthEvents.USER_REGISTERED ->
                    projector.on(jsonMapper.readValue(record.value(), AuthEvents.UserRegistered.class));
            case AuthEvents.USER_LOGGED_IN ->
                    projector.on(jsonMapper.readValue(record.value(), AuthEvents.UserLoggedIn.class));
            default -> Mono.empty(); // other consumers (security alerts, analytics) handle the rest
        };
        // Listener threads are dedicated (not event-loop), so blocking here is correct; failures are retried by the container.
        work.block(Duration.ofSeconds(10));
    }
}
