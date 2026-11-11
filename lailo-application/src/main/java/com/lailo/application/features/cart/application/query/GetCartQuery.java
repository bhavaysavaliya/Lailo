package com.lailo.application.features.cart.application.query;

import com.lailo.application.common.cqrs.Query;

import java.util.UUID;

/**
 * Reads one shopper's cart. Also how checkout obtains the lines to snapshot, which is why it returns live
 * prices and availability rather than the stored values.
 *
 * @param userId whose cart to read
 */
public record GetCartQuery(UUID userId) implements Query<CartView> {
}
