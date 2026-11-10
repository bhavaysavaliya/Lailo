package com.lailo.application.features.product.application.command;

import com.lailo.application.common.cqrs.CommandHandler;
import com.lailo.application.common.outbox.OutboxPublisher;
import com.lailo.application.common.storage.ObjectStorage;
import com.lailo.application.features.product.domain.ProductMedia;
import com.lailo.application.features.product.domain.event.ProductEvents;
import com.lailo.application.features.product.infrastructure.persistence.ProductMediaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Removes a media item. Idempotent: deleting an item that is already gone reports {@code false}.
 *
 * <p>The database row and its event commit first; the object is deleted afterwards, because an object-store
 * delete cannot be rolled back. A failed object delete therefore leaves an orphaned blob rather than a
 * gallery entry pointing at nothing - the safer of the two failures, and one a prefix sweep can clean up.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeleteProductMediaHandler implements CommandHandler<DeleteProductMediaCommand, Boolean> {

    private final ProductMediaRepository media;
    private final ObjectStorage storage;
    private final OutboxPublisher outbox;
    private final TransactionalOperator tx;
    private final Clock clock;

    /**
     * Deletes the row with its event, then the stored object.
     *
     * @param cmd the request
     * @return {@code true} if an item was removed, {@code false} if there was nothing to remove
     */
    @Override
    public Mono<Boolean> handle(DeleteProductMediaCommand cmd) {
        return media.findById(cmd.mediaId())
                .filter(item -> item.getProductId().equals(cmd.productId()))
                .flatMap(item -> remove(item).as(tx::transactional)
                        .then(deleteObject(item.getObjectKey()))
                        .thenReturn(true))
                .defaultIfEmpty(false);
    }

    /**
     * Deletes the row and appends {@code ProductMediaRemoved}. A pending item was never published, so it
     * needs no event.
     *
     * @param item the media row
     * @return completion signal
     */
    private Mono<Void> remove(ProductMedia item) {
        Instant now = Instant.now(clock);
        Mono<?> event = item.isReady()
                ? outbox.append(ProductEvents.TOPIC, ProductEvents.AGGREGATE, item.getProductId().toString(),
                ProductEvents.PRODUCT_MEDIA_REMOVED, new ProductEvents.ProductMediaRemoved(
                        UUID.randomUUID().toString(), item.getProductId().toString(),
                        item.getId().toString(), now))
                : Mono.empty();
        return media.delete(item).then(event).then();
    }

    /**
     * Best-effort object delete: a failure is logged, never surfaced to the caller whose row is already gone.
     *
     * @param objectKey key to delete
     * @return completion signal
     */
    private Mono<Void> deleteObject(String objectKey) {
        return storage.delete(objectKey)
                .onErrorResume(ex -> {
                    log.warn("Media row deleted but object {} remains: {}", objectKey, ex.getMessage());
                    return Mono.empty();
                });
    }

    /**
     * Serves {@link DeleteProductMediaCommand}.
     *
     * @return the command class
     */
    @Override
    public Class<DeleteProductMediaCommand> commandType() {
        return DeleteProductMediaCommand.class;
    }
}
