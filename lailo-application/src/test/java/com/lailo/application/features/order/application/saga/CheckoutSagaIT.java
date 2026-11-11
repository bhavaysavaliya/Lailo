package com.lailo.application.features.order.application.saga;

import com.lailo.application.common.cqrs.CommandBus;
import com.lailo.application.common.saga.SagaInstance;
import com.lailo.application.common.saga.SagaStatus;
import com.lailo.application.common.saga.SagaStore;
import com.lailo.application.features.auth.domain.User;
import com.lailo.application.features.auth.infrastructure.persistence.UserRepository;
import com.lailo.application.features.order.domain.Order;
import com.lailo.application.features.order.domain.OrderStatus;
import com.lailo.application.features.order.domain.event.OrderEvents;
import com.lailo.application.features.order.infrastructure.persistence.OrderItemRepository;
import com.lailo.application.features.order.infrastructure.persistence.OrderRepository;
import com.lailo.application.features.payment.infrastructure.persistence.PaymentRepository;
import com.lailo.application.features.product.application.command.CreateProductCommand;
import com.lailo.application.features.product.application.query.ProductView;
import com.lailo.application.features.product.infrastructure.persistence.ProductRepository;
import com.lailo.application.support.CommerceFixtures;
import com.lailo.application.support.IntegrationTest;
import com.lailo.application.support.ProductFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The checkout saga driven event by event, against real PostgreSQL and the real commands.
 *
 * <p>Orders are written straight to the write model and the events are handed to the saga by hand. That is
 * deliberate: the saga is a live Kafka consumer in the shared test context, so placing a real order would
 * have the real saga racing these assertions, and the paths worth testing here - running out of stock, a
 * duplicated event - are exactly the ones that are hard to provoke through HTTP.</p>
 */
class CheckoutSagaIT extends IntegrationTest {

    @Autowired CheckoutSaga saga;
    @Autowired SagaStore sagas;
    @Autowired CommandBus commandBus;
    @Autowired UserRepository users;
    @Autowired OrderRepository orders;
    @Autowired OrderItemRepository orderItems;
    @Autowired PaymentRepository payments;
    @Autowired ProductRepository products;

    /**
     * Creates a product with a known stock figure.
     *
     * @param name display name
     * @param stock units available
     * @return the created product
     */
    private ProductView product(String name, int stock) {
        return commandBus.dispatch(new CreateProductCommand(ProductFixtures
                .draft(name, ProductFixtures.uniqueCategory())
                .price(new BigDecimal("500.00"))
                .stockQuantity(stock)
                .build())).block();
    }

    /**
     * Reads a product's remaining stock from the write model.
     *
     * @param productId the product
     * @return units available
     */
    private int stockOf(UUID productId) {
        return products.findById(productId).block().getStockQuantity();
    }

    /**
     * Reads the saga run for an order.
     *
     * @param orderId the order
     * @return the saga instance
     */
    private SagaInstance sagaFor(UUID orderId) {
        return sagas.find(CheckoutSaga.SAGA_TYPE, orderId.toString()).block();
    }

    /**
     * The happy first step: stock is reserved and a payment is opened.
     * <ul>
     *   <li><b>Given</b> an order for two units of a product with five in stock</li>
     *   <li><b>When</b> the saga handles {@code OrderPlaced}</li>
     *   <li><b>Then</b> the saga moves to {@code PAYMENT_PENDING}, two units are gone from stock, and a
     *       pending payment exists for the order</li>
     * </ul>
     */
    @Test
    void reservesStockAndOpensAPayment() {
        User shopper = CommerceFixtures.signedInUser(users, uniqueEmail());
        ProductView product = product("Saga Happy Path", 5);
        UUID productId = UUID.fromString(product.id());
        Order order = CommerceFixtures.pendingOrder(orders, orderItems, shopper.getId(), productId, 2,
                new BigDecimal("500.00"));

        saga.on(CommerceFixtures.placedEvent(order, productId, 2, new BigDecimal("500.00"))).block();

        SagaInstance instance = sagaFor(order.getId());
        assertThat(instance.getStep()).isEqualTo(CheckoutSaga.PAYMENT_PENDING);
        assertThat(instance.getStatus()).isEqualTo(SagaStatus.RUNNING);
        assertThat(stockOf(productId)).isEqualTo(3);
        assertThat(payments.findByOrderIdOrderByCreatedAtDesc(order.getId()).collectList().block())
                .hasSize(1);
    }

    /**
     * An order that cannot be fulfilled is cancelled, and nothing is left half-done.
     * <ul>
     *   <li><b>Given</b> an order for three units of a product with only one in stock - the race that
     *       happens when two shoppers check out the last items at once</li>
     *   <li><b>When</b> the saga handles {@code OrderPlaced}</li>
     *   <li><b>Then</b> the reservation fails as one unit, the saga ends {@code COMPENSATED} recording
     *       {@code PRODUCT_OUT_OF_STOCK}, the order is cancelled as {@code OUT_OF_STOCK}, the stock is
     *       untouched because the reservation was all-or-nothing, and no payment was ever opened</li>
     * </ul>
     */
    @Test
    void cancelsAnOrderItCannotReserveStockFor() {
        User shopper = CommerceFixtures.signedInUser(users, uniqueEmail());
        ProductView product = product("Saga Last Unit", 1);
        UUID productId = UUID.fromString(product.id());
        Order order = CommerceFixtures.pendingOrder(orders, orderItems, shopper.getId(), productId, 3,
                new BigDecimal("500.00"));

        saga.on(CommerceFixtures.placedEvent(order, productId, 3, new BigDecimal("500.00"))).block();

        SagaInstance instance = sagaFor(order.getId());
        assertThat(instance.getStep()).isEqualTo(CheckoutSaga.COMPENSATED);
        assertThat(instance.getStatus()).isEqualTo(SagaStatus.COMPENSATED);
        assertThat(instance.getLastError()).contains("PRODUCT_OUT_OF_STOCK");
        Order cancelled = orders.findById(order.getId()).block();
        assertThat(cancelled.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(cancelled.getCancelReason().name()).isEqualTo("OUT_OF_STOCK");
        assertThat(stockOf(productId)).isEqualTo(1);
        assertThat(payments.findByOrderIdOrderByCreatedAtDesc(order.getId()).collectList().block()).isEmpty();
    }

    /**
     * A redelivered {@code OrderPlaced} does not reserve the stock or take the money twice.
     * <ul>
     *   <li><b>Given</b> an order whose {@code OrderPlaced} event the saga has already handled</li>
     *   <li><b>When</b> the identical event is handled again, as at-least-once delivery guarantees it
     *       eventually will be</li>
     *   <li><b>Then</b> stock has moved only once and there is still exactly one payment: the saga's
     *       one-shot start is what prevents the double charge, not the consumer's offset</li>
     * </ul>
     */
    @Test
    void ignoresARedeliveredOrderPlacedEvent() {
        User shopper = CommerceFixtures.signedInUser(users, uniqueEmail());
        ProductView product = product("Saga Duplicate", 10);
        UUID productId = UUID.fromString(product.id());
        Order order = CommerceFixtures.pendingOrder(orders, orderItems, shopper.getId(), productId, 2,
                new BigDecimal("500.00"));
        OrderEvents.OrderPlaced event = CommerceFixtures.placedEvent(order, productId, 2,
                new BigDecimal("500.00"));
        saga.on(event).block();

        saga.on(event).block();

        assertThat(stockOf(productId)).isEqualTo(8);
        assertThat(payments.findByOrderIdOrderByCreatedAtDesc(order.getId()).collectList().block())
                .hasSize(1);
    }

    /**
     * A cancellation gives the reserved units back exactly once.
     * <ul>
     *   <li><b>Given</b> an order whose stock the saga has reserved</li>
     *   <li><b>When</b> {@code OrderCancelled} is handled, and then handled a second time</li>
     *   <li><b>Then</b> the units are returned once and the saga ends {@code COMPENSATED} - a second release
     *       would invent stock that does not exist, which is why the compensation step is claimed one-shot
     *       rather than retried</li>
     * </ul>
     */
    @Test
    void releasesReservedStockExactlyOnceOnCancellation() {
        User shopper = CommerceFixtures.signedInUser(users, uniqueEmail());
        ProductView product = product("Saga Compensation", 6);
        UUID productId = UUID.fromString(product.id());
        Order order = CommerceFixtures.pendingOrder(orders, orderItems, shopper.getId(), productId, 2,
                new BigDecimal("500.00"));
        saga.on(CommerceFixtures.placedEvent(order, productId, 2, new BigDecimal("500.00"))).block();
        assertThat(stockOf(productId)).isEqualTo(4);

        OrderEvents.OrderCancelled cancelled = OrderEvents.OrderCancelled.builder()
                .eventId(UUID.randomUUID().toString())
                .orderId(order.getId().toString())
                .orderNumber(order.getOrderNumber())
                .userId(shopper.getId().toString())
                .reason("REQUESTED_BY_CUSTOMER")
                .items(List.of(OrderEvents.Line.builder()
                        .productId(productId.toString())
                        .productName("Saga Product")
                        .productSlug("saga-product")
                        .quantity(2)
                        .unitPrice(new BigDecimal("500.00"))
                        .lineTotal(new BigDecimal("1000.00"))
                        .build()))
                .occurredAt(order.getPlacedAt().plusSeconds(60))
                .build();

        saga.on(cancelled).block();
        saga.on(cancelled).block();

        assertThat(stockOf(productId)).isEqualTo(6);
        SagaInstance instance = sagaFor(order.getId());
        assertThat(instance.getStep()).isEqualTo(CheckoutSaga.COMPENSATED);
        assertThat(instance.getStatus()).isEqualTo(SagaStatus.COMPENSATED);
    }

    /**
     * A cancellation that arrives before anything was reserved undoes nothing.
     * <ul>
     *   <li><b>Given</b> an order the saga never got past its out-of-stock ending</li>
     *   <li><b>When</b> the resulting {@code OrderCancelled} is handled</li>
     *   <li><b>Then</b> the stock is unchanged, because the saga knows from its own step that it has nothing
     *       to give back</li>
     * </ul>
     */
    @Test
    void doesNotReleaseStockItNeverReserved() {
        User shopper = CommerceFixtures.signedInUser(users, uniqueEmail());
        ProductView product = product("Saga Nothing Reserved", 1);
        UUID productId = UUID.fromString(product.id());
        Order order = CommerceFixtures.pendingOrder(orders, orderItems, shopper.getId(), productId, 3,
                new BigDecimal("500.00"));
        saga.on(CommerceFixtures.placedEvent(order, productId, 3, new BigDecimal("500.00"))).block();

        saga.on(OrderEvents.OrderCancelled.builder()
                .eventId(UUID.randomUUID().toString())
                .orderId(order.getId().toString())
                .orderNumber(order.getOrderNumber())
                .userId(shopper.getId().toString())
                .reason("OUT_OF_STOCK")
                .items(List.of(OrderEvents.Line.builder()
                        .productId(productId.toString())
                        .productName("Saga Product")
                        .productSlug("saga-product")
                        .quantity(3)
                        .unitPrice(new BigDecimal("500.00"))
                        .lineTotal(new BigDecimal("1500.00"))
                        .build()))
                .occurredAt(order.getPlacedAt().plusSeconds(10))
                .build()).block();

        assertThat(stockOf(productId)).isEqualTo(1);
    }
}
