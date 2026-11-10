package com.lailo.application.features.product.domain;

import lombok.Builder;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The editable part of a product, shared by the create and update commands. Built with
 * {@code ProductDetails.builder()}, which normalises and validates: strings are trimmed, blanks become
 * {@code null} and the price is scaled to two decimals, so the write model, events and read model all agree.
 *
 * @param name display name
 * @param description long description, or {@code null}
 * @param brand brand name, or {@code null}
 * @param category catalogue category
 * @param price price in INR, greater than zero, scale 2
 * @param stockQuantity units available, zero or more
 */
@Builder
public record ProductDetails(String name, String description, String brand, String category,
                             BigDecimal price, int stockQuantity) {

    /**
     * Normalises the input, then enforces the invariants the database also enforces, so a direct caller fails
     * loudly instead of hitting a constraint violation.
     */
    public ProductDetails {
        name = trimToNull(name);
        description = trimToNull(description);
        brand = trimToNull(brand);
        category = trimToNull(category);
        price = price == null ? null : price.setScale(2, RoundingMode.HALF_UP);
        if (name == null) {
            throw new IllegalArgumentException("name is required");
        }
        if (category == null) {
            throw new IllegalArgumentException("category is required");
        }
        if (price == null || price.signum() <= 0) {
            throw new IllegalArgumentException("price must be greater than zero");
        }
        if (stockQuantity < 0) {
            throw new IllegalArgumentException("stockQuantity must not be negative");
        }
    }

    /**
     * Trims a value, mapping blanks to {@code null} so an omitted field never stores an empty string.
     *
     * @param value raw value
     * @return the trimmed value, or {@code null} when absent or blank
     */
    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
