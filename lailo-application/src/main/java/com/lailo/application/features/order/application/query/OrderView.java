package com.lailo.application.features.order.application.query;

import lombok.Builder;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * An order as the API returns it, from either side of the CQRS split.
 *
 * <p>{@code bucket} is included rather than left to the client: which tab an order belongs in is a business
 * rule ({@code OrderBucket}), and two clients deriving it from {@code status} would eventually disagree.</p>
 *
 * @param id order id
 * @param orderNumber human-readable reference
 * @param status where the order stands
 * @param bucket which tab it belongs to: {@code TO_BE_SHIPPED}, {@code SHIPPED} or {@code CANCELLED}
 * @param items the lines
 * @param totalQuantity units across all lines
 * @param itemsTotal sum of the lines
 * @param shippingFee delivery charge
 * @param totalAmount what the shopper is charged
 * @param currency ISO 4217 code
 * @param paymentMethod how the shopper chose to pay
 * @param paymentId the payment attempt in play, or {@code null}
 * @param paymentStatus where that attempt stands, or {@code null} when none exists yet
 * @param shippingRecipient name the courier asks for
 * @param shippingAddressLine delivery address on one line
 * @param courierName carrier handling the parcel, or {@code null}
 * @param trackingNumber carrier's tracking reference, or {@code null}
 * @param expectedDeliveryDate promised delivery date, or {@code null}
 * @param placedAt when the order was placed
 * @param confirmedAt when payment was accepted, or {@code null}
 * @param packedAt when it was packed, or {@code null}
 * @param shippedAt when it was handed to the courier, or {@code null}
 * @param deliveredAt when it arrived, or {@code null}
 * @param cancelledAt when it was called off, or {@code null}
 * @param cancelReason why it was called off, or {@code null}
 */
@Builder
public record OrderView(String id, String orderNumber, String status, String bucket, List<OrderItemView> items,
                        int totalQuantity, BigDecimal itemsTotal, BigDecimal shippingFee,
                        BigDecimal totalAmount, String currency, String paymentMethod, String paymentId,
                        String paymentStatus, String shippingRecipient, String shippingAddressLine,
                        String courierName, String trackingNumber, LocalDate expectedDeliveryDate,
                        Instant placedAt, Instant confirmedAt, Instant packedAt, Instant shippedAt,
                        Instant deliveredAt, Instant cancelledAt, String cancelReason) {
}
