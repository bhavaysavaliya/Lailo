package com.lailo.application.features.order.api.dto;

import com.lailo.application.features.order.domain.OrderStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Request body for moving an order along the fulfilment pipeline.
 *
 * @param target state to move to: {@code PACKED}, {@code SHIPPED} or {@code DELIVERED}
 * @param courierName carrier handling the parcel; required when shipping
 * @param trackingNumber carrier's tracking reference; required when shipping
 * @param expectedDeliveryDate promised delivery date, optional
 */
public record AdvanceFulfilmentRequest(
        @NotNull(message = "Choose a fulfilment step.") OrderStatus target,
        @Size(max = 80) String courierName,
        @Size(max = 80) String trackingNumber,
        LocalDate expectedDeliveryDate) {
}
