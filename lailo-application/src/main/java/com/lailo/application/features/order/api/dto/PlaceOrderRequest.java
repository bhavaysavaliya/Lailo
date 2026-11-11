package com.lailo.application.features.order.api.dto;

import com.lailo.application.features.payment.domain.PaymentMethod;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * Request body for checkout. There are no lines and no amounts: both come from the shopper's cart on the
 * server, which is what stops a client from choosing what it pays.
 *
 * @param address where the parcel goes
 * @param paymentMethod how the shopper wants to pay
 */
public record PlaceOrderRequest(
        @NotNull(message = "Enter a delivery address.") @Valid ShippingAddressRequest address,
        @NotNull(message = "Choose a payment method.") PaymentMethod paymentMethod) {
}
