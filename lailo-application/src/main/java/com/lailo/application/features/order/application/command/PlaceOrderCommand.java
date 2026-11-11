package com.lailo.application.features.order.application.command;

import com.lailo.application.common.cqrs.Command;
import com.lailo.application.features.order.application.query.OrderView;
import com.lailo.application.features.order.domain.ShippingAddress;
import com.lailo.application.features.payment.domain.PaymentMethod;

import java.util.UUID;

/**
 * Turns a shopper's cart into an order. The lines are not in the command: taking them from the cart server
 * side is what stops a client from ordering a product at a price it chose.
 *
 * @param userId whose cart to check out
 * @param address where the parcel goes
 * @param paymentMethod how the shopper wants to pay
 */
public record PlaceOrderCommand(UUID userId, ShippingAddress address, PaymentMethod paymentMethod)
        implements Command<OrderView> {
}
