package com.lailo.application.features.order.domain.event;

import com.lailo.application.features.order.domain.Order;
import com.lailo.application.features.order.domain.OrderItem;
import com.lailo.application.features.order.domain.OrderStatus;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Integration events for orders, published through the outbox. Three kinds of consumer read this stream:
 * the checkout saga (which drives stock and payment from it), the MongoDB projector behind the orders list,
 * and the notifier that emails and texts the shopper.
 *
 * <p>{@code OrderPlaced} carries the whole order, including its lines: a saga that had to call back into the
 * write model to learn what to reserve would be coupled to it, and a notification template that re-read the
 * order could describe a state the shopper never had.</p>
 */
public final class OrderEvents {

    public static final String TOPIC = "lailo.order.order-events.v1";
    public static final String AGGREGATE = "Order";

    public static final String ORDER_PLACED = "OrderPlaced";
    public static final String ORDER_CONFIRMED = "OrderConfirmed";
    public static final String ORDER_PACKED = "OrderPacked";
    public static final String ORDER_SHIPPED = "OrderShipped";
    public static final String ORDER_DELIVERED = "OrderDelivered";
    public static final String ORDER_CANCELLED = "OrderCancelled";

    /** Not instantiable: constants and event types only. */
    private OrderEvents() {
    }

    /**
     * One line of a placed order, as carried on the event.
     *
     * @param productId product bought
     * @param productName name at the time of the order
     * @param productSlug URL segment at the time of the order
     * @param imageUrl gallery image at the time of the order, or {@code null}
     * @param quantity units bought
     * @param unitPrice price charged per unit
     * @param lineTotal {@code unitPrice} times {@code quantity}
     */
    @Builder
    public record Line(String productId, String productName, String productSlug, String imageUrl, int quantity,
                       BigDecimal unitPrice, BigDecimal lineTotal) {

        /**
         * Builds the line from the stored snapshot.
         *
         * @param item the order line as stored
         * @return the event line
         */
        public static Line of(OrderItem item) {
            return Line.builder()
                    .productId(item.getProductId().toString())
                    .productName(item.getProductName())
                    .productSlug(item.getProductSlug())
                    .imageUrl(item.getImageUrl())
                    .quantity(item.getQuantity())
                    .unitPrice(item.getUnitPrice())
                    .lineTotal(item.getLineTotal())
                    .build();
        }
    }

    /**
     * An order was placed and is waiting for payment. This is the event the checkout saga starts from.
     *
     * @param eventId unique event id, used for idempotency and as the notification dedupe key
     * @param orderId the new order's id
     * @param orderNumber human-readable reference
     * @param userId the shopper
     * @param status status at the time of the event
     * @param items the lines, snapshotted
     * @param itemsTotal sum of the lines
     * @param shippingFee delivery charge
     * @param totalAmount what the shopper is charged
     * @param currency ISO 4217 code
     * @param paymentMethod how the shopper chose to pay
     * @param shippingAddressLine the delivery address on one line, for templates
     * @param shippingRecipient name the courier asks for
     * @param occurredAt time of placing
     */
    @Builder
    public record OrderPlaced(String eventId, String orderId, String orderNumber, String userId, String status,
                              List<Line> items, BigDecimal itemsTotal, BigDecimal shippingFee,
                              BigDecimal totalAmount, String currency, String paymentMethod,
                              String shippingAddressLine, String shippingRecipient, Instant occurredAt) {

        /**
         * Builds the event from the saved order and its lines.
         *
         * @param order the order as stored
         * @param items the lines as stored
         * @return the event, with a fresh event id
         */
        public static OrderPlaced of(Order order, List<OrderItem> items) {
            return OrderPlaced.builder()
                    .eventId(UUID.randomUUID().toString())
                    .orderId(order.getId().toString())
                    .orderNumber(order.getOrderNumber())
                    .userId(order.getUserId().toString())
                    .status(order.getStatus().name())
                    .items(items.stream().map(Line::of).toList())
                    .itemsTotal(order.getItemsTotal())
                    .shippingFee(order.getShippingFee())
                    .totalAmount(order.getTotalAmount())
                    .currency(order.getCurrency())
                    .paymentMethod(order.getPaymentMethod().name())
                    .shippingAddressLine(order.getShippingAddress().singleLine())
                    .shippingRecipient(order.getShippingAddress().fullName())
                    .occurredAt(order.getPlacedAt())
                    .build();
        }

        /**
         * Total units across the lines, which the SMS template quotes.
         *
         * @return the unit count
         */
        public int totalQuantity() {
            return items.stream().mapToInt(Line::quantity).sum();
        }
    }

    /**
     * An order's status moved. One event type covers confirmed, packed and delivered: the consumers all do
     * the same thing with them - set the status and a timestamp - so three near-identical payloads would only
     * be three ways to get that wrong.
     *
     * @param eventId unique event id
     * @param orderId the order's id
     * @param orderNumber human-readable reference
     * @param userId the shopper
     * @param status the new status
     * @param occurredAt time of the change
     */
    public record OrderStatusChanged(String eventId, String orderId, String orderNumber, String userId,
                                     String status, Instant occurredAt) {

        /**
         * Builds the event from the saved order.
         *
         * @param order the order as stored, after the transition
         * @return the event, with a fresh event id
         */
        public static OrderStatusChanged of(Order order) {
            return new OrderStatusChanged(UUID.randomUUID().toString(), order.getId().toString(),
                    order.getOrderNumber(), order.getUserId().toString(), order.getStatus().name(),
                    order.getUpdatedAt());
        }

        /**
         * The status as an enum.
         *
         * @return the parsed status
         */
        public OrderStatus statusValue() {
            return OrderStatus.valueOf(status);
        }
    }

    /**
     * An order was handed to the courier. Separate from {@code OrderStatusChanged} because it carries the
     * tracking details, which is the whole point of the "Shipped" view.
     *
     * @param eventId unique event id
     * @param orderId the order's id
     * @param orderNumber human-readable reference
     * @param userId the shopper
     * @param courierName carrier handling the parcel
     * @param trackingNumber carrier's tracking reference
     * @param expectedDeliveryDate promised delivery date, or {@code null}
     * @param occurredAt dispatch time
     */
    @Builder
    public record OrderShipped(String eventId, String orderId, String orderNumber, String userId,
                               String courierName, String trackingNumber, LocalDate expectedDeliveryDate,
                               Instant occurredAt) {

        /**
         * Builds the event from the saved order.
         *
         * @param order the order as stored, after dispatch
         * @return the event, with a fresh event id
         */
        public static OrderShipped of(Order order) {
            return OrderShipped.builder()
                    .eventId(UUID.randomUUID().toString())
                    .orderId(order.getId().toString())
                    .orderNumber(order.getOrderNumber())
                    .userId(order.getUserId().toString())
                    .courierName(order.getCourierName())
                    .trackingNumber(order.getTrackingNumber())
                    .expectedDeliveryDate(order.getExpectedDeliveryDate())
                    .occurredAt(order.getShippedAt())
                    .build();
        }
    }

    /**
     * An order was called off. The saga reads this to undo what it had already done: release the reserved
     * units and refund an authorised payment.
     *
     * @param eventId unique event id
     * @param orderId the order's id
     * @param orderNumber human-readable reference
     * @param userId the shopper
     * @param reason why it was cancelled
     * @param paymentId the payment that may need refunding, or {@code null} when none was created
     * @param items the lines, so compensation needs no call back into the write model
     * @param occurredAt cancellation time
     */
    @Builder
    public record OrderCancelled(String eventId, String orderId, String orderNumber, String userId,
                                 String reason, String paymentId, List<Line> items, Instant occurredAt) {

        /**
         * Builds the event from the cancelled order and its lines.
         *
         * @param order the order as stored, after cancellation
         * @param items the lines as stored
         * @return the event, with a fresh event id
         */
        public static OrderCancelled of(Order order, List<OrderItem> items) {
            return OrderCancelled.builder()
                    .eventId(UUID.randomUUID().toString())
                    .orderId(order.getId().toString())
                    .orderNumber(order.getOrderNumber())
                    .userId(order.getUserId().toString())
                    .reason(order.getCancelReason() == null ? null : order.getCancelReason().name())
                    .paymentId(order.getPaymentId() == null ? null : order.getPaymentId().toString())
                    .items(items.stream().map(Line::of).toList())
                    .occurredAt(order.getCancelledAt())
                    .build();
        }
    }
}
