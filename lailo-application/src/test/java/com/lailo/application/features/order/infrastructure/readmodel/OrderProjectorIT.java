package com.lailo.application.features.order.infrastructure.readmodel;

import com.lailo.application.features.order.domain.OrderBucket;
import com.lailo.application.features.order.domain.OrderStatus;
import com.lailo.application.features.order.domain.event.OrderEvents;
import com.lailo.application.features.payment.domain.PaymentStatus;
import com.lailo.application.features.payment.domain.event.PaymentEvents;
import com.lailo.application.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The orders projection against real MongoDB. Events are fed to the projector directly rather than through
 * Kafka, which is how out-of-order and duplicate delivery can be reproduced on purpose.
 */
class OrderProjectorIT extends IntegrationTest {

    @Autowired OrderProjector projector;
    @Autowired OrderReadRepository readModel;

    private static final Instant T0 = Instant.parse("2026-11-11T10:00:00Z");

    /**
     * Builds an {@code OrderPlaced} event for a fresh order.
     *
     * @param orderId the order's id
     * @param userId the shopper
     * @param occurredAt event time
     * @return the event
     */
    private static OrderEvents.OrderPlaced placed(String orderId, String userId, Instant occurredAt) {
        return OrderEvents.OrderPlaced.builder()
                .eventId(UUID.randomUUID().toString())
                .orderId(orderId)
                .orderNumber("LAILO-20261111-AAAAAA")
                .userId(userId)
                .status(OrderStatus.PENDING_PAYMENT.name())
                .items(List.of(OrderEvents.Line.builder()
                        .productId(UUID.randomUUID().toString())
                        .productName("Terracotta Planter")
                        .productSlug("terracotta-planter-1234abcd")
                        .quantity(2)
                        .unitPrice(new BigDecimal("450.00"))
                        .lineTotal(new BigDecimal("900.00"))
                        .build()))
                .itemsTotal(new BigDecimal("900.00"))
                .shippingFee(BigDecimal.ZERO)
                .totalAmount(new BigDecimal("900.00"))
                .currency("INR")
                .paymentMethod("UPI")
                .shippingAddressLine("12 Chandni Chowk, New Delhi, Delhi 110006")
                .shippingRecipient("Aarav Mehta")
                .occurredAt(occurredAt)
                .build();
    }

    /**
     * A placed order becomes a complete, self-contained document.
     * <ul>
     *   <li><b>Given</b> an {@code OrderPlaced} event with one line of two units</li>
     *   <li><b>When</b> it is projected</li>
     *   <li><b>Then</b> the document carries the reference, totals, address and line - so the orders list
     *       needs no join - and sits in the "to be shipped" tab</li>
     * </ul>
     */
    @Test
    void projectsAPlacedOrderWithItsLines() {
        String orderId = UUID.randomUUID().toString();

        projector.on(placed(orderId, UUID.randomUUID().toString(), T0)).block();

        OrderDocument doc = readModel.findById(orderId).block();
        assertThat(doc.getOrderNumber()).isEqualTo("LAILO-20261111-AAAAAA");
        assertThat(doc.getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT.name());
        assertThat(doc.getBucket()).isEqualTo(OrderBucket.TO_BE_SHIPPED.name());
        assertThat(doc.getTotalQuantity()).isEqualTo(2);
        assertThat(doc.getTotalAmount()).isEqualByComparingTo("900.00");
        assertThat(doc.getShippingAddressLine()).contains("110006");
        assertThat(doc.getItems()).hasSize(1);
        assertThat(doc.getItems().getFirst().getName()).isEqualTo("Terracotta Planter");
        assertThat(doc.getItems().getFirst().getLineTotal()).isEqualByComparingTo("900.00");
    }

    /**
     * Shipping moves the order between tabs and brings its tracking details with it.
     * <ul>
     *   <li><b>Given</b> a projected order in the "to be shipped" tab</li>
     *   <li><b>When</b> confirmation and then dispatch are projected</li>
     *   <li><b>Then</b> the document ends up {@code SHIPPED} in the "shipped" tab with the courier,
     *       tracking number and promised date, and keeps the confirmation timestamp</li>
     * </ul>
     */
    @Test
    void movesAnOrderIntoTheShippedTabOnDispatch() {
        String orderId = UUID.randomUUID().toString();
        String userId = UUID.randomUUID().toString();
        projector.on(placed(orderId, userId, T0)).block();

        projector.on(new OrderEvents.OrderStatusChanged(UUID.randomUUID().toString(), orderId,
                "LAILO-20261111-AAAAAA", userId, OrderStatus.CONFIRMED.name(), T0.plusSeconds(60))).block();
        projector.on(OrderEvents.OrderShipped.builder()
                .eventId(UUID.randomUUID().toString())
                .orderId(orderId)
                .orderNumber("LAILO-20261111-AAAAAA")
                .userId(userId)
                .courierName("Delhivery")
                .trackingNumber("DL123456789IN")
                .expectedDeliveryDate(LocalDate.of(2026, 11, 15))
                .occurredAt(T0.plusSeconds(120))
                .build()).block();

        OrderDocument doc = readModel.findById(orderId).block();
        assertThat(doc.getStatus()).isEqualTo(OrderStatus.SHIPPED.name());
        assertThat(doc.getBucket()).isEqualTo(OrderBucket.SHIPPED.name());
        assertThat(doc.getConfirmedAt()).isEqualTo(T0.plusSeconds(60));
        assertThat(doc.getShippedAt()).isEqualTo(T0.plusSeconds(120));
        assertThat(doc.getCourierName()).isEqualTo("Delhivery");
        assertThat(doc.getTrackingNumber()).isEqualTo("DL123456789IN");
        assertThat(doc.getExpectedDeliveryDate()).isEqualTo(LocalDate.of(2026, 11, 15));
    }

    /**
     * A replayed older event cannot drag the order backwards.
     * <ul>
     *   <li><b>Given</b> an order already projected as shipped</li>
     *   <li><b>When</b> an earlier {@code CONFIRMED} event is delivered again, as an at-least-once consumer
     *       eventually will</li>
     *   <li><b>Then</b> the document is untouched, because every write is conditional on the stored state
     *       being older than the event</li>
     * </ul>
     */
    @Test
    void ignoresAReplayedOlderEvent() {
        String orderId = UUID.randomUUID().toString();
        String userId = UUID.randomUUID().toString();
        projector.on(placed(orderId, userId, T0)).block();
        projector.on(OrderEvents.OrderShipped.builder()
                .eventId(UUID.randomUUID().toString())
                .orderId(orderId)
                .orderNumber("LAILO-20261111-AAAAAA")
                .userId(userId)
                .courierName("Delhivery")
                .trackingNumber("DL123456789IN")
                .occurredAt(T0.plusSeconds(120))
                .build()).block();

        projector.on(new OrderEvents.OrderStatusChanged(UUID.randomUUID().toString(), orderId,
                "LAILO-20261111-AAAAAA", userId, OrderStatus.CONFIRMED.name(), T0.plusSeconds(60))).block();

        OrderDocument doc = readModel.findById(orderId).block();
        assertThat(doc.getStatus()).isEqualTo(OrderStatus.SHIPPED.name());
        assertThat(doc.getBucket()).isEqualTo(OrderBucket.SHIPPED.name());
    }

    /**
     * Projecting the same event twice leaves one unchanged document.
     * <ul>
     *   <li><b>Given</b> a projected order</li>
     *   <li><b>When</b> the identical {@code OrderPlaced} event is projected again</li>
     *   <li><b>Then</b> the line is not duplicated, because lines are written with {@code $set} on an
     *       immutable snapshot rather than pushed onto an array</li>
     * </ul>
     */
    @Test
    void projectingTheSameEventTwiceDoesNotDuplicateLines() {
        String orderId = UUID.randomUUID().toString();
        OrderEvents.OrderPlaced event = placed(orderId, UUID.randomUUID().toString(), T0);

        projector.on(event).block();
        projector.on(event).block();

        assertThat(readModel.findById(orderId).block().getItems()).hasSize(1);
    }

    /**
     * Payment progress lands on the order, so one read answers "is it paid?".
     * <ul>
     *   <li><b>Given</b> a projected order</li>
     *   <li><b>When</b> its payment is projected as initiated and then authorised</li>
     *   <li><b>Then</b> the document carries the payment id and ends at {@code AUTHORIZED}, which is what
     *       keeps the orders list a single query instead of a join to a payment read model</li>
     * </ul>
     */
    @Test
    void projectsPaymentProgressOntoTheOrder() {
        String orderId = UUID.randomUUID().toString();
        String userId = UUID.randomUUID().toString();
        String paymentId = UUID.randomUUID().toString();
        projector.on(placed(orderId, userId, T0)).block();

        projector.on(PaymentEvents.PaymentInitiated.builder()
                .eventId(UUID.randomUUID().toString())
                .paymentId(paymentId)
                .orderId(orderId)
                .userId(userId)
                .amount(new BigDecimal("900.00"))
                .currency("INR")
                .method("UPI")
                .provider("simulated")
                .occurredAt(T0.plusSeconds(30))
                .build()).block();
        projector.on(PaymentEvents.PaymentAuthorized.builder()
                .eventId(UUID.randomUUID().toString())
                .paymentId(paymentId)
                .orderId(orderId)
                .userId(userId)
                .amount(new BigDecimal("900.00"))
                .currency("INR")
                .method("UPI")
                .occurredAt(T0.plusSeconds(45))
                .build()).block();

        OrderDocument doc = readModel.findById(orderId).block();
        assertThat(doc.getPaymentId()).isEqualTo(paymentId);
        assertThat(doc.getPaymentStatus()).isEqualTo(PaymentStatus.AUTHORIZED.name());
    }

    /**
     * A cancellation moves the order to its own tab and keeps the reason.
     * <ul>
     *   <li><b>Given</b> a projected order</li>
     *   <li><b>When</b> an {@code OrderCancelled} event is projected</li>
     *   <li><b>Then</b> the document is {@code CANCELLED} in the cancelled tab with its reason and
     *       timestamp, so it leaves both of the shopper's active tabs at once</li>
     * </ul>
     */
    @Test
    void movesACancelledOrderOutOfTheActiveTabs() {
        String orderId = UUID.randomUUID().toString();
        String userId = UUID.randomUUID().toString();
        projector.on(placed(orderId, userId, T0)).block();

        projector.on(OrderEvents.OrderCancelled.builder()
                .eventId(UUID.randomUUID().toString())
                .orderId(orderId)
                .orderNumber("LAILO-20261111-AAAAAA")
                .userId(userId)
                .reason("PAYMENT_FAILED")
                .items(List.of())
                .occurredAt(T0.plusSeconds(90))
                .build()).block();

        OrderDocument doc = readModel.findById(orderId).block();
        assertThat(doc.getStatus()).isEqualTo(OrderStatus.CANCELLED.name());
        assertThat(doc.getBucket()).isEqualTo(OrderBucket.CANCELLED.name());
        assertThat(doc.getCancelReason()).isEqualTo("PAYMENT_FAILED");
        assertThat(doc.getCancelledAt()).isEqualTo(T0.plusSeconds(90));
    }
}
