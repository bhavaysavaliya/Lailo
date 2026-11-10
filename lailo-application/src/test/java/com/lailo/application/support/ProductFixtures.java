package com.lailo.application.support;

import com.lailo.application.features.product.domain.ProductDetails;

import java.math.BigDecimal;
import java.util.UUID;

/** Builders for catalogue test data. A unique category per test keeps the shared databases isolated. */
public final class ProductFixtures {

    /** Not instantiable: static factories only. */
    private ProductFixtures() {
    }

    /**
     * Starts a product with sensible defaults: brand "Lailo Basics", 999.00 rupees and 10 in stock. A test
     * overrides only the fields it cares about, then calls {@code build()}.
     *
     * @param name display name
     * @param category catalogue category
     * @return a prefilled builder
     */
    public static ProductDetails.ProductDetailsBuilder draft(String name, String category) {
        return ProductDetails.builder()
                .name(name)
                .description(name + " for everyday use")
                .brand("Lailo Basics")
                .category(category)
                .price(new BigDecimal("999.00"))
                .stockQuantity(10);
    }

    /**
     * Builds product details with the default brand, price and stock.
     *
     * @param name display name
     * @param category catalogue category
     * @return normalised details
     */
    public static ProductDetails details(String name, String category) {
        return draft(name, category).build();
    }

    /**
     * A category no other test uses, so searches and counts stay deterministic.
     *
     * @return a unique category name
     */
    public static String uniqueCategory() {
        return "cat-" + UUID.randomUUID();
    }
}
