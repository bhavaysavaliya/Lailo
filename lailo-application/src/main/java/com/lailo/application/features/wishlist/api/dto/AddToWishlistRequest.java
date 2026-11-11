package com.lailo.application.features.wishlist.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Request body for saving a product.
 *
 * @param productId product to save
 * @param note optional note from the shopper, e.g. "for Diwali"
 */
public record AddToWishlistRequest(
        @NotNull(message = "Choose a product.") UUID productId,
        @Size(max = 280) String note) {
}
