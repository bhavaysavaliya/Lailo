package com.lailo.application.features.product.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.With;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Write-model aggregate (Postgres). The single source of truth; the catalogue read model is derived from it. */
@Table("products")
@Getter
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Product {

    /** Lailo sells in India only, so every price is in INR. */
    public static final String CURRENCY = "INR";

    @Id
    private UUID id;
    private String slug;
    private String name;
    private String description;
    private String brand;
    private String category;
    private BigDecimal price;
    private String currency;
    private int stockQuantity;
    private ProductStatus status;
    private Instant createdAt;
    private Instant updatedAt;
    /** Optimistic locking; null marks a new entity for Spring Data. */
    @Version
    private Long version;

    /**
     * Creates a new active catalogue entry.
     *
     * @param id new product id
     * @param details normalised product details
     * @param now creation time
     * @return an unsaved product with a {@code null} version, so Spring Data inserts it
     */
    public static Product create(UUID id, ProductDetails details, Instant now) {
        return Product.builder()
                .id(id)
                .slug(Slug.from(details.name(), id))
                .name(details.name())
                .description(details.description())
                .brand(details.brand())
                .category(details.category())
                .price(details.price())
                .currency(CURRENCY)
                .stockQuantity(details.stockQuantity())
                .status(ProductStatus.ACTIVE)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    /**
     * Returns a copy with new details applied. The slug is deliberately left untouched so published links and
     * search-engine entries survive a rename.
     *
     * @param details normalised product details
     * @param now update time
     * @return the updated product (immutable style)
     */
    public Product applyDetails(ProductDetails details, Instant now) {
        return withName(details.name())
                .withDescription(details.description())
                .withBrand(details.brand())
                .withCategory(details.category())
                .withPrice(details.price())
                .withStockQuantity(details.stockQuantity())
                .withUpdatedAt(now);
    }

    /**
     * Returns a copy withdrawn from the catalogue.
     *
     * @param now archive time
     * @return the archived product
     */
    public Product archive(Instant now) {
        return withStatus(ProductStatus.ARCHIVED).withUpdatedAt(now);
    }

    /**
     * Whether this product has been withdrawn.
     *
     * @return {@code true} if status is {@code ARCHIVED}
     */
    public boolean isArchived() {
        return status == ProductStatus.ARCHIVED;
    }
}
