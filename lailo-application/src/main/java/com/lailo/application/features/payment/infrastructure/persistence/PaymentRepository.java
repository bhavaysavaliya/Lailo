package com.lailo.application.features.payment.infrastructure.persistence;

import com.lailo.application.features.payment.domain.Payment;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** R2DBC repository for payment attempts. */
public interface PaymentRepository extends ReactiveCrudRepository<Payment, UUID> {

    /**
     * Lists an order's attempts, newest first; the first element is the one currently in play.
     *
     * @param orderId the order
     * @return the attempts
     */
    Flux<Payment> findByOrderIdOrderByCreatedAtDesc(UUID orderId);

    /**
     * Finds an attempt by the gateway's reference, which is all a webhook carries.
     *
     * @param providerReference the gateway's reference
     * @return the attempt, or empty
     */
    Mono<Payment> findByProviderReference(String providerReference);
}
