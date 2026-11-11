package com.lailo.application.features.product.infrastructure.persistence;

import com.lailo.application.features.product.domain.Product;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

/** R2DBC repository for the product write model. */
public interface ProductRepository extends ReactiveCrudRepository<Product, UUID> {

    /**
     * Finds a product by its URL segment.
     *
     * @param slug the slug
     * @return the product, or empty
     */
    Mono<Product> findBySlug(String slug);

    /**
     * Takes units out of stock, but only if that many are there.
     *
     * <p>The condition lives in the {@code WHERE} clause rather than in Java: two shoppers racing for the
     * last unit are serialised by the row lock, so one of them gets zero rows updated and is told the item is
     * gone. Reading, checking and writing in the application would oversell.</p>
     *
     * @param productId product to reserve from
     * @param quantity units to reserve
     * @param now time to record as the product's last change
     * @return 1 when the units were reserved, 0 when there were not enough
     */
    @Modifying
    @Query("""
            UPDATE products
            SET stock_quantity = stock_quantity - :quantity, updated_at = :now, version = version + 1
            WHERE id = :productId AND status = 'ACTIVE' AND stock_quantity >= :quantity
            """)
    Mono<Integer> reserveStock(UUID productId, int quantity, Instant now);

    /**
     * Puts units back, which is the compensating action for a reservation the checkout saga had to undo.
     *
     * @param productId product to release to
     * @param quantity units to release
     * @param now time to record as the product's last change
     * @return 1 when the product exists, 0 when it does not
     */
    @Modifying
    @Query("""
            UPDATE products
            SET stock_quantity = stock_quantity + :quantity, updated_at = :now, version = version + 1
            WHERE id = :productId
            """)
    Mono<Integer> releaseStock(UUID productId, int quantity, Instant now);
}
