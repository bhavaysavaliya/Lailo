package com.lailo.application.features.order.infrastructure.readmodel;

import org.springframework.data.mongodb.repository.ReactiveMongoRepository;
import reactor.core.publisher.Mono;

/** Reactive Mongo repository for the {@code orders} read model. Filtered lists use the template instead. */
public interface OrderReadRepository extends ReactiveMongoRepository<OrderDocument, String> {

    /**
     * Finds a projected order by its human-readable reference.
     *
     * @param orderNumber the reference
     * @return the document, or empty
     */
    Mono<OrderDocument> findByOrderNumber(String orderNumber);
}
