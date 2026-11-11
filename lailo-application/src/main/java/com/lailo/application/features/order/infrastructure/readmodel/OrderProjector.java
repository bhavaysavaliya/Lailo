package com.lailo.application.features.order.infrastructure.readmodel;

import com.lailo.application.features.order.domain.OrderBucket;
import com.lailo.application.features.order.domain.OrderStatus;
import com.lailo.application.features.order.domain.event.OrderEvents;
import com.lailo.application.features.payment.domain.PaymentStatus;
import com.lailo.application.features.payment.domain.event.PaymentEvents;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Instant;

/**
 * Projects order and payment events into the orders read model.
 *
 * <p>Same discipline as the catalogue projector: delivery is at-least-once and partitioned per order, but a
 * retry can still replay an older event, so every write is conditional on {@code updatedAt < occurredAt}.
 * The duplicate-key error a guarded upsert raises for an existing document is exactly the "stale event"
 * case and is swallowed.</p>
 *
 * <p>One consequence worth knowing before adding an event: two events about the same order must not share
 * an {@code occurredAt}. MongoDB keeps instants to millisecond precision, so the second of a pair stamped
 * within the same millisecond is indistinguishable from a replay and is dropped.</p>
 *
 * <p>Payment events are projected here too. The alternative - a payment read model the list query joins to -
 * would turn "my orders" back into a join, and the only thing the shopper needs to see is whether their
 * order is paid.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderProjector {

    private final ReactiveMongoTemplate mongo;

    /**
     * Projects {@code OrderPlaced}: writes the whole document, lines included.
     *
     * @param e the event
     * @return completion signal
     */
    public Mono<Void> on(OrderEvents.OrderPlaced e) {
        OrderStatus status = OrderStatus.valueOf(e.status());
        Update u = new Update()
                .set("orderNumber", e.orderNumber())
                .set("userId", e.userId())
                .set("status", e.status())
                .set("bucket", OrderBucket.of(status).name())
                .set("items", e.items().stream()
                        .map(line -> OrderItemDocument.builder()
                                .productId(line.productId())
                                .name(line.productName())
                                .slug(line.productSlug())
                                .imageUrl(line.imageUrl())
                                .quantity(line.quantity())
                                .unitPrice(line.unitPrice())
                                .lineTotal(line.lineTotal())
                                .currency(e.currency())
                                .build())
                        .toList())
                .set("totalQuantity", e.totalQuantity())
                .set("itemsTotal", e.itemsTotal())
                .set("shippingFee", e.shippingFee())
                .set("totalAmount", e.totalAmount())
                .set("currency", e.currency())
                .set("paymentMethod", e.paymentMethod())
                .set("shippingRecipient", e.shippingRecipient())
                .set("shippingAddressLine", e.shippingAddressLine())
                .set("placedAt", e.occurredAt());
        return apply(e.orderId(), e.occurredAt(), u);
    }

    /**
     * Projects a status move: sets the status, the tab it implies and the timestamp for that state.
     *
     * @param e the event
     * @return completion signal
     */
    public Mono<Void> on(OrderEvents.OrderStatusChanged e) {
        OrderStatus status = e.statusValue();
        Update u = new Update()
                .set("status", e.status())
                .set("bucket", OrderBucket.of(status).name())
                .setOnInsert("placedAt", e.occurredAt());
        timestampFor(status).ifPresent(field -> u.set(field, e.occurredAt()));
        return apply(e.orderId(), e.occurredAt(), u);
    }

    /**
     * Projects {@code OrderShipped}: the order leaves "to be shipped" and gains its tracking details.
     *
     * @param e the event
     * @return completion signal
     */
    public Mono<Void> on(OrderEvents.OrderShipped e) {
        Update u = new Update()
                .set("status", OrderStatus.SHIPPED.name())
                .set("bucket", OrderBucket.SHIPPED.name())
                .set("courierName", e.courierName())
                .set("trackingNumber", e.trackingNumber())
                .set("expectedDeliveryDate", e.expectedDeliveryDate())
                .set("shippedAt", e.occurredAt())
                .setOnInsert("placedAt", e.occurredAt());
        return apply(e.orderId(), e.occurredAt(), u);
    }

    /**
     * Projects {@code OrderCancelled}.
     *
     * @param e the event
     * @return completion signal
     */
    public Mono<Void> on(OrderEvents.OrderCancelled e) {
        Update u = new Update()
                .set("status", OrderStatus.CANCELLED.name())
                .set("bucket", OrderBucket.CANCELLED.name())
                .set("cancelReason", e.reason())
                .set("cancelledAt", e.occurredAt())
                .setOnInsert("placedAt", e.occurredAt());
        return apply(e.orderId(), e.occurredAt(), u);
    }

    /**
     * Projects {@code PaymentInitiated}: records which attempt is in play, so the client can resume it.
     *
     * @param e the event
     * @return completion signal
     */
    public Mono<Void> on(PaymentEvents.PaymentInitiated e) {
        Update u = new Update()
                .set("paymentId", e.paymentId())
                .set("paymentStatus", PaymentStatus.PENDING.name())
                .setOnInsert("placedAt", e.occurredAt());
        return apply(e.orderId(), e.occurredAt(), u);
    }

    /**
     * Projects {@code PaymentAuthorized}.
     *
     * @param e the event
     * @return completion signal
     */
    public Mono<Void> on(PaymentEvents.PaymentAuthorized e) {
        Update u = new Update()
                .set("paymentId", e.paymentId())
                .set("paymentStatus", PaymentStatus.AUTHORIZED.name())
                .setOnInsert("placedAt", e.occurredAt());
        return apply(e.orderId(), e.occurredAt(), u);
    }

    /**
     * Projects {@code PaymentFailed}.
     *
     * @param e the event
     * @return completion signal
     */
    public Mono<Void> on(PaymentEvents.PaymentFailed e) {
        Update u = new Update()
                .set("paymentId", e.paymentId())
                .set("paymentStatus", PaymentStatus.FAILED.name())
                .setOnInsert("placedAt", e.occurredAt());
        return apply(e.orderId(), e.occurredAt(), u);
    }

    /**
     * Projects {@code PaymentRefunded}.
     *
     * @param e the event
     * @return completion signal
     */
    public Mono<Void> on(PaymentEvents.PaymentRefunded e) {
        Update u = new Update()
                .set("paymentStatus", PaymentStatus.REFUNDED.name())
                .setOnInsert("placedAt", e.occurredAt());
        return apply(e.orderId(), e.occurredAt(), u);
    }

    /**
     * The document field that records when a status was entered.
     *
     * @param status the status just entered
     * @return the field name, or empty for statuses with their own dedicated event
     */
    private static java.util.Optional<String> timestampFor(OrderStatus status) {
        return switch (status) {
            case CONFIRMED -> java.util.Optional.of("confirmedAt");
            case PACKED -> java.util.Optional.of("packedAt");
            case DELIVERED -> java.util.Optional.of("deliveredAt");
            case SHIPPED, CANCELLED, PENDING_PAYMENT -> java.util.Optional.empty();
        };
    }

    /**
     * Upserts the document, but only when the stored copy is older than this event.
     *
     * @param orderId document id (same as the order id)
     * @param occurredAt event time, stored as {@code updatedAt}
     * @param update the update to apply
     * @return completion signal
     */
    private Mono<Void> apply(String orderId, Instant occurredAt, Update update) {
        Criteria criteria = Criteria.where("_id").is(orderId)
                .orOperator(Criteria.where("updatedAt").lt(occurredAt),
                        Criteria.where("updatedAt").exists(false));
        return mongo.findAndModify(Query.query(criteria), update.set("updatedAt", occurredAt),
                        FindAndModifyOptions.options().upsert(true), OrderDocument.class)
                .then()
                .onErrorResume(DuplicateKeyException.class, ex -> {
                    log.debug("Skipped stale order event for {} (newer state already projected)", orderId);
                    return Mono.empty();
                });
    }
}
