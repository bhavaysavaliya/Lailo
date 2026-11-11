package com.lailo.application.features.cart.application.command;

import com.lailo.application.common.cqrs.Command;
import com.lailo.application.features.cart.application.query.CartView;

import java.util.UUID;

/**
 * Puts units of a product in a shopper's cart. Adding a product already in the cart raises that line rather
 * than creating a second one.
 *
 * @param userId whose cart to change
 * @param productId product to add
 * @param quantity units to add, at least one
 */
public record AddToCartCommand(UUID userId, UUID productId, int quantity) implements Command<CartView> {
}
