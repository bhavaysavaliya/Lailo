package com.lailo.application.features.product.config;

import com.lailo.application.features.product.domain.event.ProductEvents;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/** Infrastructure beans for the catalogue feature, and its configuration properties. */
@Configuration
@EnableConfigurationProperties(MediaProperties.class)
public class ProductBeansConfig {

    /**
     * Declares the product event topic explicitly (not by broker auto-create). The record key is the product
     * id, so all events of one product land in the same partition and stay ordered.
     *
     * @param partitions partition count
     * @param replicas replication factor
     * @return the topic definition applied at startup
     */
    @Bean
    NewTopic productEventsTopic(@Value("${lailo.kafka.product-events.partitions:3}") int partitions,
                                @Value("${lailo.kafka.product-events.replicas:1}") short replicas) {
        return TopicBuilder.name(ProductEvents.TOPIC).partitions(partitions).replicas(replicas).build();
    }
}
