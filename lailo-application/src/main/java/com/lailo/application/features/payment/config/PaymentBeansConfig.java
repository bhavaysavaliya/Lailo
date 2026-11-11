package com.lailo.application.features.payment.config;

import com.lailo.application.features.payment.domain.event.PaymentEvents;
import com.lailo.application.features.payment.infrastructure.gateway.PaymentGateway;
import com.lailo.application.features.payment.infrastructure.gateway.SimulatedPaymentGateway;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

import java.time.Clock;

/** Infrastructure beans for the payment feature, and its configuration properties. */
@Slf4j
@Configuration
@EnableConfigurationProperties(PaymentProperties.class)
public class PaymentBeansConfig {

    /**
     * Declares the payment event topic explicitly rather than relying on broker auto-create. The record key
     * is the <em>order</em> id, so a retry's events stay ordered behind the original attempt's.
     *
     * @param partitions partition count
     * @param replicas replication factor
     * @return the topic definition applied at startup
     */
    @Bean
    NewTopic paymentEventsTopic(@Value("${lailo.kafka.payment-events.partitions:3}") int partitions,
                                @Value("${lailo.kafka.payment-events.replicas:1}") short replicas) {
        return TopicBuilder.name(PaymentEvents.TOPIC).partitions(partitions).replicas(replicas).build();
    }

    /**
     * Wires the gateway. Replace by defining a {@link PaymentGateway} bean for a real provider (Razorpay,
     * Cashfree, Stripe); the simulated one warns loudly so it cannot be left on by accident.
     *
     * @param properties payment settings
     * @param clock injected clock, so intent expiry is testable
     * @return the gateway adapter
     */
    @Bean
    @ConditionalOnMissingBean(PaymentGateway.class)
    PaymentGateway simulatedPaymentGateway(PaymentProperties properties, Clock clock) {
        log.warn("No payment provider configured: using the simulated gateway. "
                + "Never run this outside local development or tests.");
        return new SimulatedPaymentGateway(properties, clock);
    }
}
