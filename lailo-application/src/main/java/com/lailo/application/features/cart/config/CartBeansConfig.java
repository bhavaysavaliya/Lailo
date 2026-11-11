package com.lailo.application.features.cart.config;

import com.lailo.application.features.cart.domain.event.CartEvents;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/** Infrastructure beans for the cart feature. */
@Configuration
public class CartBeansConfig {

    /**
     * Declares the cart event topic explicitly rather than relying on broker auto-create. The record key is
     * the user id, so one shopper's cart events stay ordered within a partition.
     *
     * @param partitions partition count
     * @param replicas replication factor
     * @return the topic definition applied at startup
     */
    @Bean
    NewTopic cartEventsTopic(@Value("${lailo.kafka.cart-events.partitions:3}") int partitions,
                             @Value("${lailo.kafka.cart-events.replicas:1}") short replicas) {
        return TopicBuilder.name(CartEvents.TOPIC).partitions(partitions).replicas(replicas).build();
    }
}
