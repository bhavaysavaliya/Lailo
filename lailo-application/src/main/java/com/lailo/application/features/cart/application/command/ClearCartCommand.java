package com.lailo.application.features.cart.application.command;

import com.lailo.application.common.cqrs.Command;
import com.lailo.application.features.cart.domain.ClearCartReason;

import java.util.UUID;

/**
 * Empties a cart. Checkout dispatches this inside the transaction that creates the order, so a shopper can
 * never end up with both the order and the cart that produced it.
 *
 * @param userId whose cart to empty
 * @param reason why it is being emptied
 */
public record ClearCartCommand(UUID userId, ClearCartReason reason) implements Command<Boolean> {
}
