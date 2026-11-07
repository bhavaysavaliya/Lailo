package com.lailo.application.features.auth.config;

import com.lailo.application.features.auth.domain.event.AuthEvents;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.security.SecureRandom;
import java.time.Clock;

/**
 * Shared infrastructure beans for the auth feature; also binds {@link AuthProperties} and enables scheduling
 * (needed by the outbox relay).
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(AuthProperties.class)
public class AuthBeansConfig {

    /** Injected everywhere time matters so tests can use a fixed clock. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** Declared explicitly (not broker auto-create); keyed by user id, so ordering per user is preserved. */
    @Bean
    NewTopic userEventsTopic(@Value("${lailo.kafka.user-events.partitions:3}") int partitions,
                             @Value("${lailo.kafka.user-events.replicas:1}") short replicas) {
        return TopicBuilder.name(AuthEvents.TOPIC).partitions(partitions).replicas(replicas).build();
    }

    /**
     * Cryptographically strong random source for OTPs, refresh tokens and captcha text.
     *
     * @return a new {@code SecureRandom}
     */
    @Bean
    SecureRandom secureRandom() {
        return new SecureRandom();
    }
}
