package com.lailo.application.features.product.infrastructure.readmodel;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * Catalogue read model (MongoDB), built from product events. Denormalised so a browse or search request is a
 * single query with no joins.
 *
 * <p>Production indexes to create on {@code products}: {@code (status, category)},
 * {@code (status, price)}, {@code (status, createdAt)} and a text index on name, brand and description.
 * Automatic index creation is intentionally left off.</p>
 */
@Document("products")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductDocument {
    @Id
    private String id;
    private String slug;
    private String name;
    private String description;
    private String brand;
    private String category;
    /** Stored as Decimal128, not a string, so price filters and sorts are numeric. */
    @Field(targetType = FieldType.DECIMAL128)
    private BigDecimal price;
    private String currency;
    private Integer stockQuantity;
    private String status;
    private Instant createdAt;
    private Instant updatedAt;
    /**
     * Media keyed by media id, so projecting one item is an idempotent {@code $set} and removing it an
     * {@code $unset} - a {@code $push} into an array would duplicate on redelivery. Gallery order comes from
     * each item's position when the view is built.
     */
    private Map<String, ProductMediaDocument> media;
}
