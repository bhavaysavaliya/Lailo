package com.lailo.application.features.order.application.command;

import com.lailo.application.common.cqrs.Command;

import java.util.UUID;

/**
 * Releases a paid order to the warehouse. Dispatched by the checkout saga when the gateway authorises the
 * money; there is no API route, because a shopper cannot confirm their own order.
 *
 * @param orderId the order to confirm
 */
public record ConfirmOrderCommand(UUID orderId) implements Command<Boolean> {
}
