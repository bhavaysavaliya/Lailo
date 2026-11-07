package com.lailo.application.features.auth.application.query;

import com.lailo.application.common.web.ApiException;
import com.lailo.application.features.auth.domain.Identifier;
import com.lailo.application.features.auth.domain.User;
import com.lailo.application.features.auth.infrastructure.persistence.UserRepository;
import com.lailo.application.features.auth.infrastructure.readmodel.UserProfileDocument;
import com.lailo.application.features.auth.infrastructure.readmodel.UserProfileRepository;
import com.lailo.application.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Query side against real MongoDB (read model) with PostgreSQL fallback. */
class GetCurrentUserHandlerIT extends IntegrationTest {

    private static final Instant T = Instant.parse("2026-11-06T10:00:00Z");

    @Autowired GetCurrentUserHandler handler;
    @Autowired UserProfileRepository profiles;
    @Autowired UserRepository users;

    /**
     * The profile is served from the MongoDB read model.
     * <ul>
     *   <li><b>Given</b> a profile document in MongoDB</li>
     *   <li><b>When</b> the profile is queried</li>
     *   <li><b>Then</b> the view matches the document</li>
     * </ul>
     */
    @Test
    void servesTheMongoProjection() {
        String id = UUID.randomUUID().toString();
        profiles.save(UserProfileDocument.builder().id(id).email("projected@example.com")
                .registeredAt(T).lastLoginAt(T).lastLoginMethod("EMAIL").build()).block();

        StepVerifier.create(handler.handle(new GetCurrentUserQuery(UUID.fromString(id))))
                .expectNext(new UserProfileView(id, "projected@example.com", null, T, T))
                .verifyComplete();
    }

    /**
     * While the projection is missing, the profile falls back to Postgres.
     * <ul>
     *   <li><b>Given</b> a user in Postgres with no MongoDB document</li>
     *   <li><b>When</b> the profile is queried</li>
     *   <li><b>Then</b> the view is built from the Postgres row</li>
     * </ul>
     */
    @Test
    void fallsBackToPostgresWhileTheProjectionCatchesUp() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS); // Postgres timestamp precision
        User saved = users.save(User.register(UUID.randomUUID(), Identifier.parse(uniquePhone(), "91"), now)).block();

        StepVerifier.create(handler.handle(new GetCurrentUserQuery(saved.getId())))
                .assertNext(view -> {
                    assertThat(view.id()).isEqualTo(saved.getId().toString());
                    assertThat(view.phone()).isEqualTo(saved.getPhone());
                    assertThat(view.registeredAt()).isEqualTo(now);
                })
                .verifyComplete();
    }

    /**
     * An unknown user is treated as an ended session.
     * <ul>
     *   <li><b>Given</b> a random user id present in neither store</li>
     *   <li><b>When</b> the profile is queried</li>
     *   <li><b>Then</b> it fails with {@code SESSION_INVALID}</li>
     * </ul>
     */
    @Test
    void unknownUserIsTreatedAsAnEndedSession() {
        StepVerifier.create(handler.handle(new GetCurrentUserQuery(UUID.randomUUID())))
                .expectErrorSatisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("SESSION_INVALID"))
                .verify();
    }
}
