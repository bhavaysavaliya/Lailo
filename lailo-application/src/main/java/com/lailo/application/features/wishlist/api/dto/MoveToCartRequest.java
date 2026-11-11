package com.lailo.application.features.wishlist.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Request body for moving a saved product into the cart. The body is optional; omitting it moves one unit.
 *
 * @param quantity units to add to the cart, or {@code null} for one
 */
public record MoveToCartRequest(@Min(1) @Max(20) Integer quantity) {

    /**
     * Units to add, treating an omitted quantity as one.
     *
     * @return the requested quantity, or 1
     */
    public int quantityOrOne() {
        return quantity == null ? 1 : quantity;
    }
}
