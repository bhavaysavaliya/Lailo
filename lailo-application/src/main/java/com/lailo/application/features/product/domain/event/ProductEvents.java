package com.lailo.application.features.product.domain.event;

import com.lailo.application.features.product.domain.Product;
import com.lailo.application.features.product.domain.ProductMedia;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Integration events published to Kafka via the outbox. Payloads are versioned by {@code eventType};
 * {@code occurredAt} orders them, which lets the projector drop events it has already superseded.
 */
public final class ProductEvents {

    public static final String TOPIC = "lailo.catalog.product-events.v1";
    public static final String AGGREGATE = "Product";

    public static final String PRODUCT_CREATED = "ProductCreated";
    public static final String PRODUCT_UPDATED = "ProductUpdated";
    public static final String PRODUCT_ARCHIVED = "ProductArchived";
    public static final String PRODUCT_MEDIA_ADDED = "ProductMediaAdded";
    public static final String PRODUCT_MEDIA_REMOVED = "ProductMediaRemoved";

    /** Not instantiable: constants and event types only. */
    private ProductEvents() {
    }

    /**
     * A product entered the catalogue.
     *
     * @param eventId unique event id, used for idempotency
     * @param productId the new product's id
     * @param slug URL segment
     * @param name display name
     * @param description long description, or {@code null}
     * @param brand brand name, or {@code null}
     * @param category catalogue category
     * @param price price in {@code currency}
     * @param currency ISO 4217 code, always {@code INR} today
     * @param stockQuantity units available
     * @param occurredAt creation time
     */
    @Builder
    public record ProductCreated(String eventId, String productId, String slug, String name, String description,
                                 String brand, String category, BigDecimal price, String currency,
                                 int stockQuantity, Instant occurredAt) {

        /**
         * Builds the event from the saved aggregate.
         *
         * @param product the product as stored
         * @return the event, with a fresh event id
         */
        public static ProductCreated of(Product product) {
            return ProductCreated.builder()
                    .eventId(UUID.randomUUID().toString())
                    .productId(product.getId().toString())
                    .slug(product.getSlug())
                    .name(product.getName())
                    .description(product.getDescription())
                    .brand(product.getBrand())
                    .category(product.getCategory())
                    .price(product.getPrice())
                    .currency(product.getCurrency())
                    .stockQuantity(product.getStockQuantity())
                    .occurredAt(product.getUpdatedAt())
                    .build();
        }
    }

    /**
     * A product's details changed. Carries the full state, so a consumer needs no previous event.
     *
     * @param eventId unique event id
     * @param productId the product's id
     * @param slug URL segment (never changes after creation)
     * @param name display name
     * @param description long description, or {@code null}
     * @param brand brand name, or {@code null}
     * @param category catalogue category
     * @param price price in {@code currency}
     * @param currency ISO 4217 code
     * @param stockQuantity units available
     * @param occurredAt update time
     */
    @Builder
    public record ProductUpdated(String eventId, String productId, String slug, String name, String description,
                                 String brand, String category, BigDecimal price, String currency,
                                 int stockQuantity, Instant occurredAt) {

        /**
         * Builds the event from the saved aggregate.
         *
         * @param product the product as stored
         * @return the event, with a fresh event id
         */
        public static ProductUpdated of(Product product) {
            return ProductUpdated.builder()
                    .eventId(UUID.randomUUID().toString())
                    .productId(product.getId().toString())
                    .slug(product.getSlug())
                    .name(product.getName())
                    .description(product.getDescription())
                    .brand(product.getBrand())
                    .category(product.getCategory())
                    .price(product.getPrice())
                    .currency(product.getCurrency())
                    .stockQuantity(product.getStockQuantity())
                    .occurredAt(product.getUpdatedAt())
                    .build();
        }
    }

    /**
     * A product was withdrawn from the catalogue.
     *
     * @param eventId unique event id
     * @param productId the product's id
     * @param occurredAt archive time
     */
    public record ProductArchived(String eventId, String productId, Instant occurredAt) {

        /**
         * Builds the event from the archived aggregate.
         *
         * @param product the product as stored
         * @return the event, with a fresh event id
         */
        public static ProductArchived of(Product product) {
            return new ProductArchived(UUID.randomUUID().toString(), product.getId().toString(),
                    product.getUpdatedAt());
        }
    }

    /**
     * An image or video was confirmed in the object store and belongs to the product.
     *
     * @param eventId unique event id
     * @param productId owning product
     * @param mediaId the media item's id
     * @param type {@code IMAGE} or {@code VIDEO}
     * @param objectKey key in the media bucket; the public URL is derived from it at read time, so moving to
     *        a CDN needs no reprojection
     * @param contentType stored content type
     * @param sizeBytes stored size
     * @param position gallery position
     * @param occurredAt confirmation time
     */
    @Builder
    public record ProductMediaAdded(String eventId, String productId, String mediaId, String type,
                                    String objectKey, String contentType, long sizeBytes, int position,
                                    Instant occurredAt) {

        /**
         * Builds the event from the stored media row.
         *
         * @param media the media item as stored
         * @return the event, with a fresh event id
         */
        public static ProductMediaAdded of(ProductMedia media) {
            return ProductMediaAdded.builder()
                    .eventId(UUID.randomUUID().toString())
                    .productId(media.getProductId().toString())
                    .mediaId(media.getId().toString())
                    .type(media.getType().name())
                    .objectKey(media.getObjectKey())
                    .contentType(media.getContentType())
                    .sizeBytes(media.getSizeBytes())
                    .position(media.getPosition())
                    .occurredAt(media.getReadyAt())
                    .build();
        }
    }

    /**
     * A media item was removed from the product.
     *
     * @param eventId unique event id
     * @param productId owning product
     * @param mediaId the removed media item's id
     * @param occurredAt removal time
     */
    public record ProductMediaRemoved(String eventId, String productId, String mediaId, Instant occurredAt) {
    }
}
