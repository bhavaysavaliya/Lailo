package com.lailo.application.features.cart.application.command;

import com.lailo.application.common.cqrs.Command;
import com.lailo.application.features.cart.application.query.CartView;

import java.util.UUID;

/**
 * Takes one product out of a shopper's cart.
 *
 * @param userId whose cart to change
 * @param productId product to remove
 */
public record RemoveFromCartCommand(UUID userId, UUID productId) implements Command<CartView> {
}
