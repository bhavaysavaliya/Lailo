package com.lailo.application.features.wishlist.config;

import com.lailo.application.features.wishlist.domain.event.WishlistEvents;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/** Infrastructure beans for the wishlist feature. */
@Configuration
public class WishlistBeansConfig {

    /**
     * Declares the wishlist event topic explicitly rather than relying on broker auto-create. The record key
     * is the user id, so one shopper's events stay ordered within a partition.
     *
     * @param partitions partition count
     * @param replicas replication factor
     * @return the topic definition applied at startup
     */
    @Bean
    NewTopic wishlistEventsTopic(@Value("${lailo.kafka.wishlist-events.partitions:3}") int partitions,
                                 @Value("${lailo.kafka.wishlist-events.replicas:1}") short replicas) {
        return TopicBuilder.name(WishlistEvents.TOPIC).partitions(partitions).replicas(replicas).build();
    }
}
