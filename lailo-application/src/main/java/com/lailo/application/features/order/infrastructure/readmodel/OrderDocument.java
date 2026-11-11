package com.lailo.application.features.order.infrastructure.readmodel;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Orders read model (MongoDB), built from order events. Denormalised so "my orders, to be shipped, page 1"
 * is one indexed query with no joins to the lines.
 *
 * <p>{@code bucket} is stored, not computed at query time: the tab an order belongs to is derived from its
 * status by {@code OrderBucket} when the event is projected, which keeps the list query a single equality
 * match instead of an {@code $in} over a status list the API would have to know.</p>
 *
 * <p>Production indexes to create on {@code orders}: {@code (userId, bucket, placedAt)},
 * {@code (userId, placedAt)} and a unique index on {@code orderNumber}. Automatic index creation is
 * intentionally left off.</p>
 */
@Document("orders")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderDocument {
    @Id
    private String id;
    private String orderNumber;
    private String userId;
    private String status;
    /** Which tab this order belongs to, derived from the status when the event was projected. */
    private String bucket;
    private List<OrderItemDocument> items;
    private Integer totalQuantity;
    @Field(targetType = FieldType.DECIMAL128)
    private BigDecimal itemsTotal;
    @Field(targetType = FieldType.DECIMAL128)
    private BigDecimal shippingFee;
    @Field(targetType = FieldType.DECIMAL128)
    private BigDecimal totalAmount;
    private String currency;
    private String paymentMethod;
    private String paymentId;
    private String paymentStatus;
    private String shippingRecipient;
    private String shippingAddressLine;
    private String courierName;
    private String trackingNumber;
    private LocalDate expectedDeliveryDate;
    private Instant placedAt;
    private Instant confirmedAt;
    private Instant packedAt;
    private Instant shippedAt;
    private Instant deliveredAt;
    private Instant cancelledAt;
    private String cancelReason;
    private Instant updatedAt;
}
