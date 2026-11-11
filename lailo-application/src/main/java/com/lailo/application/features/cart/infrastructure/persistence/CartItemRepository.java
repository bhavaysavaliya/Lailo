package com.lailo.application.features.cart.infrastructure.persistence;

import com.lailo.application.features.cart.domain.CartItem;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** R2DBC repository for cart lines. The aggregate is keyed by user, so every query starts there. */
public interface CartItemRepository extends ReactiveCrudRepository<CartItem, UUID> {

    /**
     * Reads a shopper's whole cart in the order items were added.
     *
     * @param userId the shopper
     * @return the lines, oldest first
     */
    Flux<CartItem> findByUserIdOrderByCreatedAt(UUID userId);

    /**
     * Finds the line holding one product, which is what makes adding the same product twice an update.
     *
     * @param userId the shopper
     * @param productId the product
     * @return the line, or empty
     */
    Mono<CartItem> findByUserIdAndProductId(UUID userId, UUID productId);

    /**
     * Deletes one line.
     *
     * @param userId the shopper
     * @param productId the product to remove
     * @return number of lines deleted: 1, or 0 when the cart did not hold it
     */
    Mono<Long> deleteByUserIdAndProductId(UUID userId, UUID productId);

    /**
     * Empties a cart.
     *
     * @param userId the shopper
     * @return number of lines deleted
     */
    Mono<Long> deleteByUserId(UUID userId);

    /**
     * Counts the lines in a cart, used for the badge and to reject an empty checkout.
     *
     * @param userId the shopper
     * @return number of lines
     */
    Mono<Long> countByUserId(UUID userId);
}
