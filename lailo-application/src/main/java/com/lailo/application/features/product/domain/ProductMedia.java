package com.lailo.application.features.product.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.With;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One image or video belonging to a product (write model). The row is created when the upload URL is issued
 * and only becomes {@code READY} once the bytes are confirmed in the object store, so a failed or abandoned
 * upload can never show up in the catalogue.
 */
@Table("product_media")
@Getter
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductMedia {

    @Id
    private UUID id;
    private UUID productId;
    private MediaType type;
    private String objectKey;
    private String contentType;
    private long sizeBytes;
    private MediaStatus status;
    private int position;
    private Instant createdAt;
    private Instant readyAt;
    /** Optimistic locking; null marks a new entity for Spring Data. */
    @Version
    private Long version;

    /**
     * Reserves a slot for an upload that has not happened yet.
     *
     * @param id new media id
     * @param productId owning product
     * @param type image or video
     * @param contentType normalised content type the client must send
     * @param sizeBytes exact size the client declared
     * @param position zero-based gallery position
     * @param now reservation time
     * @return an unsaved, {@code PENDING} media item
     */
    public static ProductMedia reserve(UUID id, UUID productId, MediaType type, String contentType,
                                       long sizeBytes, int position, Instant now) {
        return ProductMedia.builder()
                .id(id)
                .productId(productId)
                .type(type)
                .objectKey(MediaKeys.objectKey(productId, id, contentType))
                .contentType(contentType)
                .sizeBytes(sizeBytes)
                .status(MediaStatus.PENDING)
                .position(position)
                .createdAt(now)
                .build();
    }

    /**
     * Returns a copy marked ready, with the size the object store actually reports.
     *
     * @param storedSizeBytes size read back from the store
     * @param now confirmation time
     * @return the ready media item
     */
    public ProductMedia markReady(long storedSizeBytes, Instant now) {
        return withSizeBytes(storedSizeBytes).withStatus(MediaStatus.READY).withReadyAt(now);
    }

    /**
     * Whether the bytes have been confirmed in the object store.
     *
     * @return {@code true} if status is {@code READY}
     */
    public boolean isReady() {
        return status == MediaStatus.READY;
    }
}
