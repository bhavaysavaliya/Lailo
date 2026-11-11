package com.lailo.application.features.order.application.command;

import com.lailo.application.common.cqrs.CommandBus;
import com.lailo.application.common.web.ApiException;
import com.lailo.application.features.auth.domain.User;
import com.lailo.application.features.auth.infrastructure.persistence.UserRepository;
import com.lailo.application.features.order.domain.CancelReason;
import com.lailo.application.features.order.domain.Order;
import com.lailo.application.features.order.domain.OrderBucket;
import com.lailo.application.features.order.domain.OrderStatus;
import com.lailo.application.features.order.infrastructure.persistence.OrderItemRepository;
import com.lailo.application.features.order.infrastructure.persistence.OrderRepository;
import com.lailo.application.features.product.application.command.CreateProductCommand;
import com.lailo.application.support.CommerceFixtures;
import com.lailo.application.support.IntegrationTest;
import com.lailo.application.support.ProductFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.r2dbc.core.DatabaseClient;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The fulfilment pipeline against real PostgreSQL. This is the write side of the two views the shopper
 * asked for: shipping an order is exactly what moves it out of "to be shipped".
 */
class AdvanceOrderFulfilmentHandlerIT extends IntegrationTest {

    @Autowired CommandBus commandBus;
    @Autowired UserRepository users;
    @Autowired OrderRepository orders;
    @Autowired OrderItemRepository orderItems;
    @Autowired DatabaseClient db;

    /**
     * Stores a confirmed order for a throwaway shopper and product.
     *
     * @return the saved order, ready to be packed or shipped
     */
    private Order confirmedOrder() {
        User shopper = CommerceFixtures.signedInUser(users, uniqueEmail());
        UUID productId = UUID.fromString(commandBus.dispatch(new CreateProductCommand(
                ProductFixtures.details("Fulfilment Product", ProductFixtures.uniqueCategory())))
                .block().id());
        return CommerceFixtures.confirmedOrder(orders, orderItems, shopper.getId(), productId);
    }

    /**
     * Reads the order event types recorded in the outbox for one order, oldest first.
     *
     * @param orderId the order
     * @return the recorded event types
     */
    private List<String> orderEvents(UUID orderId) {
        return db.sql("""
                        SELECT event_type FROM outbox_messages
                        WHERE aggregate_type = 'Order' AND aggregate_id = :id ORDER BY created_at
                        """)
                .bind("id", orderId.toString()).map(r -> r.get(0, String.class)).all().collectList().block();
    }

    /**
     * An order walks the whole pipeline and lands in the shipped tab.
     * <ul>
     *   <li><b>Given</b> a confirmed order</li>
     *   <li><b>When</b> it is packed, shipped with courier details, then delivered</li>
     *   <li><b>Then</b> each step stamps its own timestamp, the tracking details are stored, the order moves
     *       from the "to be shipped" tab into "shipped" at the shipping step and stays there once delivered,
     *       and one event per step is in the outbox</li>
     * </ul>
     */
    @Test
    void walksAnOrderFromConfirmedToDelivered() {
        Order order = confirmedOrder();
        assertThat(order.bucket()).isEqualTo(OrderBucket.TO_BE_SHIPPED);

        commandBus.dispatch(AdvanceOrderFulfilmentCommand.builder()
                .orderId(order.getId()).target(OrderStatus.PACKED).build()).block();
        Order packed = orders.findById(order.getId()).block();
        assertThat(packed.getStatus()).isEqualTo(OrderStatus.PACKED);
        assertThat(packed.getPackedAt()).isNotNull();
        assertThat(packed.bucket()).isEqualTo(OrderBucket.TO_BE_SHIPPED);

        commandBus.dispatch(AdvanceOrderFulfilmentCommand.builder()
                .orderId(order.getId())
                .target(OrderStatus.SHIPPED)
                .courierName("Delhivery")
                .trackingNumber("DL123456789IN")
                .expectedDeliveryDate(LocalDate.of(2026, 11, 15))
                .build()).block();
        Order shipped = orders.findById(order.getId()).block();
        assertThat(shipped.getStatus()).isEqualTo(OrderStatus.SHIPPED);
        assertThat(shipped.getShippedAt()).isNotNull();
        assertThat(shipped.getCourierName()).isEqualTo("Delhivery");
        assertThat(shipped.getTrackingNumber()).isEqualTo("DL123456789IN");
        assertThat(shipped.getExpectedDeliveryDate()).isEqualTo(LocalDate.of(2026, 11, 15));
        assertThat(shipped.bucket()).isEqualTo(OrderBucket.SHIPPED);

        commandBus.dispatch(AdvanceOrderFulfilmentCommand.builder()
                .orderId(order.getId()).target(OrderStatus.DELIVERED).build()).block();
        Order delivered = orders.findById(order.getId()).block();
        assertThat(delivered.getStatus()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(delivered.getDeliveredAt()).isNotNull();
        assertThat(delivered.bucket()).isEqualTo(OrderBucket.SHIPPED);

        assertThat(orderEvents(order.getId()))
                .containsExactly("OrderPacked", "OrderShipped", "OrderDelivered");
    }

    /**
     * A small parcel may skip the packing step.
     * <ul>
     *   <li><b>Given</b> a confirmed order that was never marked packed</li>
     *   <li><b>When</b> it is shipped</li>
     *   <li><b>Then</b> the move succeeds, because confirmed-to-shipped is a legal step</li>
     * </ul>
     */
    @Test
    void allowsShippingStraightFromConfirmed() {
        Order order = confirmedOrder();

        Boolean moved = commandBus.dispatch(AdvanceOrderFulfilmentCommand.builder()
                .orderId(order.getId())
                .target(OrderStatus.SHIPPED)
                .courierName("Blue Dart")
                .trackingNumber("BD987654321IN")
                .build()).block();

        assertThat(moved).isTrue();
        assertThat(orders.findById(order.getId()).block().getStatus()).isEqualTo(OrderStatus.SHIPPED);
    }

    /**
     * Steps cannot be skipped.
     * <ul>
     *   <li><b>Given</b> a confirmed order that has not shipped</li>
     *   <li><b>When</b> it is marked delivered</li>
     *   <li><b>Then</b> it fails with {@code ORDER_STATUS_INVALID} and the order has not moved, so
     *       "delivered" always means a courier really carried it</li>
     * </ul>
     */
    @Test
    void refusesToSkipStraightToDelivered() {
        Order order = confirmedOrder();

        assertThatThrownBy(() -> commandBus.dispatch(AdvanceOrderFulfilmentCommand.builder()
                .orderId(order.getId()).target(OrderStatus.DELIVERED).build()).block())
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("ORDER_STATUS_INVALID");

        assertThat(orders.findById(order.getId()).block().getStatus()).isEqualTo(OrderStatus.CONFIRMED);
    }

    /**
     * Repeating a step changes nothing.
     * <ul>
     *   <li><b>Given</b> an order that has already been packed</li>
     *   <li><b>When</b> it is packed again</li>
     *   <li><b>Then</b> the call reports that nothing moved and no second event is published</li>
     * </ul>
     */
    @Test
    void repeatingAStepIsANoOp() {
        Order order = confirmedOrder();
        commandBus.dispatch(AdvanceOrderFulfilmentCommand.builder()
                .orderId(order.getId()).target(OrderStatus.PACKED).build()).block();

        Boolean again = commandBus.dispatch(AdvanceOrderFulfilmentCommand.builder()
                .orderId(order.getId()).target(OrderStatus.PACKED).build()).block();

        assertThat(again).isFalse();
        assertThat(orderEvents(order.getId())).containsExactly("OrderPacked");
    }

    /**
     * A dispatched parcel can no longer be cancelled.
     * <ul>
     *   <li><b>Given</b> an order already handed to the courier</li>
     *   <li><b>When</b> the shopper cancels it</li>
     *   <li><b>Then</b> it fails with {@code ORDER_NOT_CANCELLABLE}, because recalling a parcel in transit is
     *       a return rather than a cancellation</li>
     * </ul>
     */
    @Test
    void refusesToCancelAnOrderThatHasShipped() {
        Order order = confirmedOrder();
        commandBus.dispatch(AdvanceOrderFulfilmentCommand.builder()
                .orderId(order.getId())
                .target(OrderStatus.SHIPPED)
                .courierName("Ecom Express")
                .trackingNumber("EE555000111IN")
                .build()).block();

        assertThatThrownBy(() -> commandBus.dispatch(new CancelOrderCommand(order.getId(),
                order.getUserId(), CancelReason.REQUESTED_BY_CUSTOMER)).block())
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("ORDER_NOT_CANCELLABLE");
    }

    /**
     * Cancelling twice is harmless.
     * <ul>
     *   <li><b>Given</b> a confirmed order</li>
     *   <li><b>When</b> it is cancelled, then cancelled again</li>
     *   <li><b>Then</b> the first call reports the cancellation and the second reports nothing to do, so a
     *       retried request cannot produce a second {@code OrderCancelled} event and therefore cannot
     *       release the same stock twice</li>
     * </ul>
     */
    @Test
    void cancellingTwiceEmitsOneEvent() {
        Order order = confirmedOrder();

        Boolean first = commandBus.dispatch(new CancelOrderCommand(order.getId(), order.getUserId(),
                CancelReason.REQUESTED_BY_CUSTOMER)).block();
        Boolean second = commandBus.dispatch(new CancelOrderCommand(order.getId(), order.getUserId(),
                CancelReason.REQUESTED_BY_CUSTOMER)).block();

        assertThat(first).isTrue();
        assertThat(second).isFalse();
        assertThat(orderEvents(order.getId())).containsExactly("OrderCancelled");
        assertThat(orders.findById(order.getId()).block().getCancelReason())
                .isEqualTo(CancelReason.REQUESTED_BY_CUSTOMER);
    }

    /**
     * An order is only ever visible to the shopper who placed it.
     * <ul>
     *   <li><b>Given</b> one shopper's order and a second shopper</li>
     *   <li><b>When</b> the second shopper tries to cancel it</li>
     *   <li><b>Then</b> it fails with {@code ORDER_NOT_FOUND} - the same answer as a missing order, so the
     *       API cannot be used to discover that an order id exists</li>
     * </ul>
     */
    @Test
    void hidesAnotherShoppersOrder() {
        Order order = confirmedOrder();
        User stranger = CommerceFixtures.signedInUser(users, uniqueEmail());

        assertThatThrownBy(() -> commandBus.dispatch(new CancelOrderCommand(order.getId(),
                stranger.getId(), CancelReason.REQUESTED_BY_CUSTOMER)).block())
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("ORDER_NOT_FOUND");
    }
}
