package com.lailo.application.features.order.config;

import com.lailo.application.features.order.domain.event.OrderEvents;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/** Infrastructure beans for the order feature, and its configuration properties. */
@Configuration
@EnableConfigurationProperties(CheckoutProperties.class)
public class OrderBeansConfig {

    /**
     * Declares the order event topic explicitly rather than relying on broker auto-create. The record key is
     * the order id, so every event about one order - and therefore every saga step for it - stays ordered in
     * one partition.
     *
     * @param partitions partition count
     * @param replicas replication factor
     * @return the topic definition applied at startup
     */
    @Bean
    NewTopic orderEventsTopic(@Value("${lailo.kafka.order-events.partitions:3}") int partitions,
                              @Value("${lailo.kafka.order-events.replicas:1}") short replicas) {
        return TopicBuilder.name(OrderEvents.TOPIC).partitions(partitions).replicas(replicas).build();
    }
}
