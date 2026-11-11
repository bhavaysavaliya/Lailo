package com.lailo.application.support;

import com.lailo.application.common.notification.EmailSender;
import com.lailo.application.common.notification.MessageSender;
import com.lailo.application.common.web.ClientInfo;
import com.lailo.application.features.auth.domain.event.AuthEvents;
import com.lailo.application.features.auth.infrastructure.otp.OtpSender;
import com.lailo.application.features.cart.domain.event.CartEvents;
import com.lailo.application.features.order.domain.event.OrderEvents;
import com.lailo.application.features.payment.domain.event.PaymentEvents;
import com.lailo.application.features.product.domain.event.ProductEvents;
import com.lailo.application.features.wishlist.domain.event.WishlistEvents;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.head;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

/**
 * Base class for every test that touches persistence: the real application context against embedded
 * PostgreSQL 18.6, MongoDB 7.0.15, Redis and Kafka. Repositories are never mocked.
 *
 * <p>All subclasses must share ONE cached Spring context, so keep bean overrides here and never add a
 * context-changing annotation to a subclass. {@code @AutoConfigureWebTestClient} sits here for that reason
 * rather than on the few classes that make HTTP calls: a second context would start a second set of Kafka
 * consumers in the same groups, and every switch between the two contexts then rebalances every group -
 * gaps long enough to stall a projection and fail an unrelated test.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1,
        topics = {AuthEvents.TOPIC, ProductEvents.TOPIC, CartEvents.TOPIC, WishlistEvents.TOPIC,
                OrderEvents.TOPIC, PaymentEvents.TOPIC},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
public abstract class IntegrationTest {

    /** The only test double: OTP delivery is an external system (SMS / email gateway). Spy = real bean, verifiable. */
    @MockitoSpyBean
    protected OtpSender otpSender;

    /** Email provider: an external boundary, so it is spied rather than mocked - the real adapter still runs. */
    @MockitoSpyBean
    protected EmailSender emailSender;

    /** SMS provider: the same reasoning as {@link #emailSender}. */
    @MockitoSpyBean
    protected MessageSender messageSender;

    /**
     * Starts the embedded PostgreSQL and Redis and points R2DBC, Flyway and Redis at them.
     * MongoDB and Kafka are wired by their own test integrations.
     *
     * @param registry dynamic property registry
     */
    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        EmbeddedInfrastructure.start();
        int pg = EmbeddedInfrastructure.postgresPort();
        registry.add("spring.r2dbc.url", () -> "r2dbc:postgresql://localhost:" + pg + "/postgres");
        registry.add("spring.r2dbc.username", () -> "postgres");
        registry.add("spring.r2dbc.password", () -> "postgres");
        registry.add("spring.flyway.url", () -> "jdbc:postgresql://localhost:" + pg + "/postgres");
        registry.add("spring.flyway.user", () -> "postgres");
        registry.add("spring.flyway.password", () -> "postgres");
        registry.add("spring.data.redis.port", EmbeddedInfrastructure::redisPort);
        registry.add("lailo.storage.endpoint", EmbeddedInfrastructure::objectStorageUrl);
        registry.add("lailo.storage.public-base-url", () -> EmbeddedInfrastructure.objectStorageUrl() + "/lailo-media");
    }

    /**
     * Resets the object-store stub and restores the defaults every test can rely on: an upload succeeds, a
     * delete succeeds, and an object that no test uploaded does not exist.
     */
    @BeforeEach
    void resetObjectStorage() {
        WireMockServer storage = EmbeddedInfrastructure.objectStorage();
        storage.resetAll();
        storage.stubFor(put(urlMatching("/lailo-media/.*"))
                .willReturn(aResponse().withStatus(200).withHeader("ETag", "\"stub-etag\"")));
        storage.stubFor(head(urlMatching("/lailo-media/.*")).willReturn(aResponse().withStatus(404)));
        storage.stubFor(delete(urlMatching("/lailo-media/.*")).willReturn(aResponse().withStatus(204)));
    }

    /**
     * Makes the stubbed store report an object as present, as it would after a successful upload.
     *
     * @param objectKey key the object was uploaded under
     * @param contentType content type the store should report
     * @param sizeBytes size the store should report
     */
    protected static void givenStoredObject(String objectKey, String contentType, long sizeBytes) {
        EmbeddedInfrastructure.objectStorage().stubFor(head(urlPathEqualTo("/lailo-media/" + objectKey))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", contentType)
                        .withHeader("Content-Length", Long.toString(sizeBytes))
                        .withHeader("ETag", "\"stub-etag\"")));
    }

    /** Unique caller per test so Redis rate-limit counters never leak between tests. */
    protected static ClientInfo uniqueClient() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        return new ClientInfo("10." + r.nextInt(256) + "." + r.nextInt(256) + "." + r.nextInt(1, 255), "JUnit");
    }

    /**
     * Random unique email, so tests never collide on shared database state.
     *
     * @return an address under {@code example.com}
     */
    protected static String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }

    /** Random valid Indian mobile number (10 digits, starts with 6-9). */
    protected static String uniquePhone() {
        return Long.toString(ThreadLocalRandom.current().nextLong(6_000_000_000L, 10_000_000_000L));
    }
}
