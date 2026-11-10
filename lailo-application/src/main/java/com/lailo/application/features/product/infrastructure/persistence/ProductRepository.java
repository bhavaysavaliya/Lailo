package com.lailo.application.features.product.infrastructure.persistence;

import com.lailo.application.features.product.domain.Product;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

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
}
