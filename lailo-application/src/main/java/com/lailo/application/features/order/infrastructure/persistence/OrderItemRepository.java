package com.lailo.application.features.order.infrastructure.persistence;

import com.lailo.application.features.order.domain.OrderItem;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

/** R2DBC repository for order lines. */
public interface OrderItemRepository extends ReactiveCrudRepository<OrderItem, UUID> {

    /**
     * Reads an order's lines.
     *
     * @param orderId the order
     * @return the lines
     */
    Flux<OrderItem> findByOrderId(UUID orderId);
}
