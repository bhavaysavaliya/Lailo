package com.lailo.application.common.notification;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** R2DBC repository for {@link NotificationDelivery} claims. */
public interface NotificationDeliveryRepository extends ReactiveCrudRepository<NotificationDelivery, UUID> {

    /**
     * Finds the existing claim for one key and channel, which is how a duplicate-key insert is resolved.
     *
     * @param dedupeKey idempotency key
     * @param channel the channel
     * @return the claim, or empty
     */
    Mono<NotificationDelivery> findByDedupeKeyAndChannel(String dedupeKey, NotificationChannel channel);
}
