package com.lailo.application.features.product.application.command;

import com.lailo.application.common.cqrs.CommandHandler;
import com.lailo.application.common.storage.ObjectStorage;
import com.lailo.application.common.storage.PresignedUpload;
import com.lailo.application.common.storage.StorageProperties;
import com.lailo.application.features.product.application.ProductErrors;
import com.lailo.application.features.product.config.MediaProperties;
import com.lailo.application.features.product.domain.MediaKind;
import com.lailo.application.features.product.domain.MediaType;
import com.lailo.application.features.product.domain.Product;
import com.lailo.application.features.product.domain.ProductMedia;
import com.lailo.application.features.product.infrastructure.persistence.ProductMediaRepository;
import com.lailo.application.features.product.infrastructure.persistence.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Reserves a media slot and signs a direct upload URL.
 *
 * <p>No event is published here: a reserved slot is not catalogue content until the bytes are confirmed, so
 * an abandoned upload leaves nothing but a {@code PENDING} row.</p>
 */
@Component
@RequiredArgsConstructor
public class RequestProductMediaUploadHandler
        implements CommandHandler<RequestProductMediaUploadCommand, RequestProductMediaUploadCommand.Result> {

    private final ProductRepository products;
    private final ProductMediaRepository media;
    private final ObjectStorage storage;
    private final MediaProperties mediaProps;
    private final StorageProperties storageProps;
    private final Clock clock;

    /**
     * Validates the request against the product and the media policy, then reserves the slot.
     *
     * @param cmd the request
     * @return the upload ticket, or {@code PRODUCT_NOT_FOUND} / {@code PRODUCT_ARCHIVED} /
     *         {@code MEDIA_TYPE_UNSUPPORTED} / {@code MEDIA_TOO_LARGE} / {@code MEDIA_LIMIT_REACHED}
     */
    @Override
    public Mono<RequestProductMediaUploadCommand.Result> handle(RequestProductMediaUploadCommand cmd) {
        return Mono.defer(() -> {
            String contentType = MediaKind.normalise(cmd.contentType());
            Optional<MediaType> kind = MediaKind.typeOf(contentType);
            if (kind.isEmpty()) {
                return Mono.error(ProductErrors.mediaTypeUnsupported(cmd.contentType()));
            }
            MediaType type = kind.get();
            long maxBytes = type == MediaType.VIDEO ? mediaProps.video().maxBytes() : mediaProps.image().maxBytes();
            if (cmd.sizeBytes() <= 0 || cmd.sizeBytes() > maxBytes) {
                return Mono.error(ProductErrors.mediaTooLarge(maxBytes));
            }
            return check(cmd, type, contentType);
        });
    }

    /**
     * Checks the product and the per-product cap, then reserves the slot.
     *
     * @param cmd the request
     * @param type image or video
     * @param contentType normalised content type
     * @return the upload ticket
     */
    private Mono<RequestProductMediaUploadCommand.Result> check(RequestProductMediaUploadCommand cmd,
                                                                MediaType type, String contentType) {
        return products.findById(cmd.productId())
                .switchIfEmpty(Mono.error(ProductErrors.notFound(cmd.productId())))
                .flatMap(product -> product.isArchived()
                        ? Mono.<Product>error(ProductErrors.archived())
                        : Mono.just(product))
                .flatMap(product -> media.countByProductId(product.getId()))
                .flatMap(count -> count >= mediaProps.maxPerProduct()
                        ? Mono.<Long>error(ProductErrors.mediaLimitReached(mediaProps.maxPerProduct()))
                        : Mono.just(count))
                .flatMap(count -> reserve(cmd, type, contentType, count.intValue()));
    }

    /**
     * Stores the pending row and signs the URL for its object key.
     *
     * @param cmd the request
     * @param type image or video
     * @param contentType normalised content type
     * @param position gallery position, appended after the existing items
     * @return the upload ticket
     */
    private Mono<RequestProductMediaUploadCommand.Result> reserve(RequestProductMediaUploadCommand cmd,
                                                                  MediaType type, String contentType,
                                                                  int position) {
        Instant now = Instant.now(clock);
        ProductMedia reserved = ProductMedia.reserve(UUID.randomUUID(), cmd.productId(), type, contentType,
                cmd.sizeBytes(), position, now);
        return media.save(reserved).map(saved -> {
            PresignedUpload upload = storage.presignUpload(saved.getObjectKey(), contentType,
                    saved.getSizeBytes(), storageProps.uploadUrlTtl());
            return RequestProductMediaUploadCommand.Result.builder()
                    .mediaId(saved.getId().toString())
                    .type(type.name())
                    .uploadUrl(upload.url())
                    .httpMethod(upload.httpMethod())
                    .headers(upload.headers())
                    .expiresAt(upload.expiresAt())
                    .confirmUrl("/api/v1/products/" + saved.getProductId() + "/media/" + saved.getId())
                    .build();
        });
    }

    /**
     * Serves {@link RequestProductMediaUploadCommand}.
     *
     * @return the command class
     */
    @Override
    public Class<RequestProductMediaUploadCommand> commandType() {
        return RequestProductMediaUploadCommand.class;
    }
}
