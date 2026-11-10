package com.lailo.application.features.product.infrastructure.persistence;

import com.lailo.application.features.product.domain.MediaStatus;
import com.lailo.application.features.product.domain.ProductMedia;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** R2DBC repository for product media rows. */
public interface ProductMediaRepository extends ReactiveCrudRepository<ProductMedia, UUID> {

    /**
     * Lists a product's media in gallery order, restricted to one upload state.
     *
     * @param productId owning product
     * @param status state to list, usually {@code READY}
     * @return the media items, ordered by position
     */
    Flux<ProductMedia> findByProductIdAndStatusOrderByPosition(UUID productId, MediaStatus status);

    /**
     * Lists every media row of a product, whatever its state.
     *
     * @param productId owning product
     * @return the media items, ordered by position
     */
    Flux<ProductMedia> findByProductIdOrderByPosition(UUID productId);

    /**
     * Counts a product's media, pending uploads included, which is what the per-product cap applies to.
     *
     * @param productId owning product
     * @return the number of rows
     */
    Mono<Long> countByProductId(UUID productId);
}
