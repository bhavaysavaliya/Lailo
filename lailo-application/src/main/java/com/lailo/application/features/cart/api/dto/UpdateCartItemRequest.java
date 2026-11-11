package com.lailo.application.features.cart.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Request body for setting a cart line's quantity. A quantity of zero is rejected rather than treated as a
 * removal, so a UI bug cannot silently delete a line.
 *
 * @param quantity units the line should hold
 */
public record UpdateCartItemRequest(
        @NotNull(message = "Enter a quantity.") @Min(1) @Max(20) Integer quantity) {
}
