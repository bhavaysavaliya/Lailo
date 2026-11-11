package com.lailo.application.features.order.infrastructure.persistence;

import com.lailo.application.features.order.domain.Order;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** R2DBC repository for the order write model. */
public interface OrderRepository extends ReactiveCrudRepository<Order, UUID> {

    /**
     * Finds an order by the reference a shopper reads out on a support call.
     *
     * @param orderNumber human-readable reference
     * @return the order, or empty
     */
    Mono<Order> findByOrderNumber(String orderNumber);

    /**
     * Lists a shopper's orders newest first. This serves read-your-write right after checkout; the paged,
     * filtered list is served from MongoDB.
     *
     * @param userId the shopper
     * @return the orders
     */
    Flux<Order> findByUserIdOrderByPlacedAtDesc(UUID userId);
}
