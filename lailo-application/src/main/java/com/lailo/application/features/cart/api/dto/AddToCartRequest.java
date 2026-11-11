package com.lailo.application.features.cart.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Request body for adding to the cart. The upper bound matches the per-line cap, so an absurd quantity is
 * rejected before any database or catalogue work happens.
 *
 * @param productId product to add
 * @param quantity units to add; defaults to one when omitted
 */
public record AddToCartRequest(
        @NotNull(message = "Choose a product.") UUID productId,
        @NotNull(message = "Enter a quantity.") @Min(1) @Max(20) Integer quantity) {

    /**
     * Units to add, treating an omitted quantity as one.
     *
     * @return the requested quantity, or 1
     */
    public int quantityOrOne() {
        return quantity == null ? 1 : quantity;
    }
}
