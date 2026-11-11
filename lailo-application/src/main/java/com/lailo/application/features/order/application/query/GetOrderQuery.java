package com.lailo.application.features.order.application.query;

import com.lailo.application.common.cqrs.Query;

import java.util.UUID;

/**
 * Reads one order.
 *
 * @param orderId the order to read
 * @param userId the caller, so an order can only be read by the shopper who placed it
 */
public record GetOrderQuery(UUID orderId, UUID userId) implements Query<OrderView> {
}
