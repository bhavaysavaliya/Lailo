package com.lailo.application.features.order.domain;

import com.lailo.application.features.payment.domain.PaymentMethod;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.With;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Embedded;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Order write-model aggregate (Postgres) and the single source of truth for what a shopper bought.
 *
 * <p>Every transition is a method here rather than a status assignment at a call site, and each one stamps
 * its own timestamp: together they are the audit trail support reads, and they make an illegal move
 * (shipping a cancelled order) impossible to express.</p>
 */
@Table("orders")
@Getter
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Order {

    @Id
    private UUID id;
    private String orderNumber;
    private UUID userId;
    private OrderStatus status;
    private BigDecimal itemsTotal;
    private BigDecimal shippingFee;
    private BigDecimal totalAmount;
    private String currency;
    private PaymentMethod paymentMethod;
    /** The attempt currently in play; a declined one stays in {@code payments} and is replaced here. */
    private UUID paymentId;
    /** Snapshot of where the parcel goes, stored in the {@code ship_*} columns. */
    @Embedded.Nullable(prefix = "ship_")
    private ShippingAddress shippingAddress;
    private Instant placedAt;
    private Instant confirmedAt;
    private Instant packedAt;
    private Instant shippedAt;
    private Instant deliveredAt;
    private Instant cancelledAt;
    private CancelReason cancelReason;
    private String courierName;
    private String trackingNumber;
    private LocalDate expectedDeliveryDate;
    private Instant updatedAt;
    /** Optimistic locking; null marks a new entity for Spring Data. */
    @Version
    private Long version;

    /**
     * Creates a freshly placed order, waiting for its payment to be authorised.
     *
     * @param id new order id
     * @param orderNumber human-readable reference
     * @param userId the shopper
     * @param totals priced-up basket
     * @param paymentMethod how the shopper chose to pay
     * @param address where the parcel goes
     * @param now time of placing
     * @return an unsaved order with a {@code null} version, so Spring Data inserts it
     */
    public static Order place(UUID id, String orderNumber, UUID userId, OrderTotals totals,
                              PaymentMethod paymentMethod, ShippingAddress address, Instant now) {
        return Order.builder()
                .id(id)
                .orderNumber(orderNumber)
                .userId(userId)
                .status(OrderStatus.PENDING_PAYMENT)
                .itemsTotal(totals.itemsTotal())
                .shippingFee(totals.shippingFee())
                .totalAmount(totals.totalAmount())
                .currency(totals.currency())
                .paymentMethod(paymentMethod)
                .shippingAddress(address)
                .placedAt(now)
                .updatedAt(now)
                .build();
    }

    /**
     * Returns a copy confirmed after payment, which is what releases it to the warehouse.
     *
     * @param now confirmation time
     * @return the confirmed order
     */
    public Order confirm(Instant now) {
        return withStatus(OrderStatus.CONFIRMED).withConfirmedAt(now).withUpdatedAt(now);
    }

    /**
     * Returns a copy marked as picked and packed.
     *
     * @param now packing time
     * @return the packed order
     */
    public Order pack(Instant now) {
        return withStatus(OrderStatus.PACKED).withPackedAt(now).withUpdatedAt(now);
    }

    /**
     * Returns a copy handed to the courier, which moves it out of "to be shipped".
     *
     * @param courierName carrier handling the parcel
     * @param trackingNumber carrier's tracking reference
     * @param expectedDeliveryDate promised delivery date, or {@code null}
     * @param now dispatch time
     * @return the shipped order
     */
    public Order ship(String courierName, String trackingNumber, LocalDate expectedDeliveryDate, Instant now) {
        return withStatus(OrderStatus.SHIPPED)
                .withCourierName(courierName)
                .withTrackingNumber(trackingNumber)
                .withExpectedDeliveryDate(expectedDeliveryDate)
                .withShippedAt(now)
                .withUpdatedAt(now);
    }

    /**
     * Returns a copy marked as received.
     *
     * @param now delivery time
     * @return the delivered order
     */
    public Order deliver(Instant now) {
        return withStatus(OrderStatus.DELIVERED).withDeliveredAt(now).withUpdatedAt(now);
    }

    /**
     * Returns a copy called off.
     *
     * @param reason why it was cancelled
     * @param now cancellation time
     * @return the cancelled order
     */
    public Order cancel(CancelReason reason, Instant now) {
        return withStatus(OrderStatus.CANCELLED)
                .withCancelReason(reason)
                .withCancelledAt(now)
                .withUpdatedAt(now);
    }

    /**
     * Returns a copy pointing at the payment attempt now in play.
     *
     * @param newPaymentId the attempt's id
     * @param now change time
     * @return the updated order
     */
    public Order attachPayment(UUID newPaymentId, Instant now) {
        return withPaymentId(newPaymentId).withUpdatedAt(now);
    }

    /**
     * Whether the order is still waiting to be dispatched.
     *
     * @return {@code true} while it sits in the warehouse
     */
    public boolean isAwaitingDispatch() {
        return status.isAwaitingDispatch();
    }

    /**
     * Whether the order can still be cancelled.
     *
     * @return {@code true} until it is dispatched or already cancelled
     */
    public boolean isCancellable() {
        return status.isCancellable();
    }

    /**
     * Which tab of the orders list this order belongs to.
     *
     * @return the bucket derived from the status
     */
    public OrderBucket bucket() {
        return OrderBucket.of(status);
    }
}
