package com.lailo.application.features.order.application.command;

import com.lailo.application.common.cqrs.Command;
import com.lailo.application.features.order.domain.CancelReason;

import java.util.UUID;

/**
 * Calls an order off. Used both by the shopper and by the checkout saga when stock or payment fails.
 *
 * @param orderId the order to cancel
 * @param userId the caller, or {@code null} when the saga or support is driving it; a non-null value is
 *        checked against the order's owner
 * @param reason why it is being cancelled
 */
public record CancelOrderCommand(UUID orderId, UUID userId, CancelReason reason) implements Command<Boolean> {
}
