package com.lailo.application.features.product.application.query;

import lombok.Builder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Product as returned by the API, from either the catalogue read model or the write model. Built by
 * {@link ProductViewMapper}, which is also what turns object keys into public media URLs.
 *
 * @param id product id
 * @param slug URL segment
 * @param name display name
 * @param description long description, or {@code null}
 * @param brand brand name, or {@code null}
 * @param category catalogue category
 * @param price price in {@code currency}
 * @param currency ISO 4217 code
 * @param stockQuantity units available
 * @param status {@code ACTIVE} or {@code ARCHIVED}
 * @param createdAt when the product entered the catalogue
 * @param updatedAt when it last changed
 * @param media images and videos in gallery order; empty when the product has none
 */
@Builder
public record ProductView(String id, String slug, String name, String description, String brand, String category,
                          BigDecimal price, String currency, int stockQuantity, String status,
                          Instant createdAt, Instant updatedAt, List<ProductMediaView> media) {
}
