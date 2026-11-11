package com.lailo.application.features.wishlist.infrastructure.persistence;

import com.lailo.application.features.wishlist.domain.WishlistItem;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** R2DBC repository for saved products. Like the cart, every query starts from the user. */
public interface WishlistItemRepository extends ReactiveCrudRepository<WishlistItem, UUID> {

    /**
     * Reads a shopper's wishlist, most recently saved first.
     *
     * @param userId the shopper
     * @return the saved entries
     */
    Flux<WishlistItem> findByUserIdOrderByCreatedAtDesc(UUID userId);

    /**
     * Finds one saved entry.
     *
     * @param userId the shopper
     * @param productId the product
     * @return the entry, or empty
     */
    Mono<WishlistItem> findByUserIdAndProductId(UUID userId, UUID productId);

    /**
     * Removes one saved entry.
     *
     * @param userId the shopper
     * @param productId the product to unsave
     * @return number of entries deleted: 1, or 0 when it was not saved
     */
    Mono<Long> deleteByUserIdAndProductId(UUID userId, UUID productId);
}
