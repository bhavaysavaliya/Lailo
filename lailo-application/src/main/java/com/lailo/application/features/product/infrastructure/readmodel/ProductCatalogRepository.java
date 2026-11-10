package com.lailo.application.features.product.infrastructure.readmodel;

import org.springframework.data.mongodb.repository.ReactiveMongoRepository;
import reactor.core.publisher.Mono;

/** Reactive Mongo repository for the {@code products} read model. Filtered search uses the template instead. */
public interface ProductCatalogRepository extends ReactiveMongoRepository<ProductDocument, String> {

    /**
     * Finds a catalogue entry by its URL segment.
     *
     * @param slug the slug
     * @return the document, or empty
     */
    Mono<ProductDocument> findBySlug(String slug);
}
