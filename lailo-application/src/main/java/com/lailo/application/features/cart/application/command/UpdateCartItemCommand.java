package com.lailo.application.features.cart.application.command;

import com.lailo.application.common.cqrs.Command;
import com.lailo.application.features.cart.application.query.CartView;

import java.util.UUID;

/**
 * Sets a line to an exact number of units. Removing a line is {@link RemoveFromCartCommand}, not a quantity
 * of zero, so "set to 0" can never be a typo that silently deletes something.
 *
 * @param userId whose cart to change
 * @param productId product whose line to set
 * @param quantity units the line should hold, at least one
 */
public record UpdateCartItemCommand(UUID userId, UUID productId, int quantity) implements Command<CartView> {
}
