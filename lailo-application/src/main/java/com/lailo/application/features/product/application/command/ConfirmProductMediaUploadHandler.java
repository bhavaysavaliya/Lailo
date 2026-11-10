package com.lailo.application.features.product.application.command;

import com.lailo.application.common.cqrs.CommandHandler;
import com.lailo.application.common.outbox.OutboxPublisher;
import com.lailo.application.common.storage.ObjectStorage;
import com.lailo.application.common.storage.StoredObject;
import com.lailo.application.features.product.application.ProductErrors;
import com.lailo.application.features.product.application.query.ProductMediaView;
import com.lailo.application.features.product.application.query.ProductViewMapper;
import com.lailo.application.features.product.domain.MediaKind;
import com.lailo.application.features.product.domain.ProductMedia;
import com.lailo.application.features.product.domain.event.ProductEvents;
import com.lailo.application.features.product.infrastructure.persistence.ProductMediaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;

/**
 * Verifies the upload against the object store before publishing it. Trusting the client here would let a
 * caller put a product in the catalogue with a gallery entry that 404s, or one whose bytes are something
 * other than what was signed for.
 */
@Component
@RequiredArgsConstructor
public class ConfirmProductMediaUploadHandler
        implements CommandHandler<ConfirmProductMediaUploadCommand, ProductMediaView> {

    private final ProductMediaRepository media;
    private final ObjectStorage storage;
    private final ProductViewMapper mapper;
    private final OutboxPublisher outbox;
    private final TransactionalOperator tx;
    private final Clock clock;

    /**
     * Reads the object's metadata, then marks the row ready and appends {@code ProductMediaAdded} in one
     * transaction. Confirming twice returns the same item without publishing a second event.
     *
     * @param cmd the request
     * @return the published media item, or {@code MEDIA_NOT_FOUND} / {@code MEDIA_UPLOAD_INCOMPLETE} /
     *         {@code MEDIA_CONTENT_MISMATCH}
     */
    @Override
    public Mono<ProductMediaView> handle(ConfirmProductMediaUploadCommand cmd) {
        return media.findById(cmd.mediaId())
                .filter(item -> item.getProductId().equals(cmd.productId()))
                .switchIfEmpty(Mono.error(ProductErrors.mediaNotFound(cmd.mediaId())))
                .flatMap(item -> item.isReady() ? Mono.just(item) : publish(item))
                .map(mapper::toView);
    }

    /**
     * Confirms the bytes and publishes the item.
     *
     * @param item the pending media row
     * @return the ready media row
     */
    private Mono<ProductMedia> publish(ProductMedia item) {
        return storage.head(item.getObjectKey())
                .switchIfEmpty(Mono.error(ProductErrors.mediaUploadIncomplete()))
                .flatMap(stored -> verify(item, stored))
                .flatMap(stored -> {
                    // The presigned URL bound the exact length, so the declared size is authoritative if a
                    // store answers HEAD without a usable Content-Length.
                    long size = stored.sizeBytes() > 0 ? stored.sizeBytes() : item.getSizeBytes();
                    ProductMedia ready = item.markReady(size, Instant.now(clock));
                    return media.save(ready)
                            .flatMap(saved -> outbox.append(ProductEvents.TOPIC, ProductEvents.AGGREGATE,
                                            saved.getProductId().toString(), ProductEvents.PRODUCT_MEDIA_ADDED,
                                            ProductEvents.ProductMediaAdded.of(saved))
                                    .thenReturn(saved))
                            .as(tx::transactional);
                });
    }

    /**
     * Checks that the stored object is the one that was reserved.
     *
     * @param item the reserved media row
     * @param stored what the object store reports
     * @return the stored object when it matches
     */
    private static Mono<StoredObject> verify(ProductMedia item, StoredObject stored) {
        String actual = MediaKind.normalise(stored.contentType());
        if (!actual.isEmpty() && !actual.equals(item.getContentType())) {
            return Mono.error(ProductErrors.mediaContentMismatch(item.getContentType(), stored.contentType()));
        }
        return Mono.just(stored);
    }

    /**
     * Serves {@link ConfirmProductMediaUploadCommand}.
     *
     * @return the command class
     */
    @Override
    public Class<ConfirmProductMediaUploadCommand> commandType() {
        return ConfirmProductMediaUploadCommand.class;
    }
}
