package com.lailo.application.support;

import com.lailo.application.common.web.ClientInfo;
import com.lailo.application.features.auth.domain.event.AuthEvents;
import com.lailo.application.features.auth.infrastructure.otp.OtpSender;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Base class for every test that touches persistence: the real application context against embedded
 * PostgreSQL 18.6, MongoDB 7.0.15, Redis and Kafka. Repositories are never mocked.
 * All subclasses share one cached Spring context, so keep bean overrides here, not in subclasses.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1, topics = AuthEvents.TOPIC, bootstrapServersProperty = "spring.kafka.bootstrap-servers")
public abstract class IntegrationTest {

    /** The only test double: OTP delivery is an external system (SMS / email gateway). Spy = real bean, verifiable. */
    @MockitoSpyBean
    protected OtpSender otpSender;

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
    }

    /** Unique caller per test so Redis rate-limit counters never leak between tests. */
    protected static ClientInfo uniqueClient() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        return new ClientInfo("10." + r.nextInt(256) + "." + r.nextInt(256) + "." + r.nextInt(1, 255), "JUnit");
    }

    protected static String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }

    /** Random valid Indian mobile number (10 digits, starts with 6-9). */
    protected static String uniquePhone() {
        return Long.toString(ThreadLocalRandom.current().nextLong(6_000_000_000L, 10_000_000_000L));
    }
}
