package com.lailo.application.features.product.application.command;

import java.util.UUID;

/**
 * One product and how many units of it a stock command applies to.
 *
 * @param productId the product
 * @param quantity units to reserve or release, always positive
 */
public record StockLine(UUID productId, int quantity) {

    /** Keeps a zero or negative quantity out of the arithmetic, where it would silently inflate stock. */
    public StockLine {
        if (productId == null) {
            throw new IllegalArgumentException("productId is required");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be greater than zero");
        }
    }
}
