package com.lailo.application.features.order.application.query;

import com.lailo.application.common.cqrs.QueryBus;
import com.lailo.application.features.order.domain.OrderBucket;
import com.lailo.application.features.order.domain.OrderStatus;
import com.lailo.application.features.order.domain.event.OrderEvents;
import com.lailo.application.features.order.infrastructure.readmodel.OrderProjector;
import com.lailo.application.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The orders list against real MongoDB: the two views the shopper asked for, "shipped" and "to be shipped".
 *
 * <p>Orders are seeded through the projector rather than by placing them, so the data under test is exact
 * and the assertions do not race the checkout saga.</p>
 */
class ListOrdersHandlerIT extends IntegrationTest {

    @Autowired QueryBus queryBus;
    @Autowired OrderProjector projector;

    private static final Instant T0 = Instant.parse("2026-11-11T10:00:00Z");

    /**
     * Projects one order for a shopper and leaves it in the requested status.
     *
     * @param userId the shopper
     * @param status status the order should end in
     * @param placedSecondsAfter how long after the base time it was placed, which orders the list
     * @return the order's id
     */
    private String seedOrder(UUID userId, OrderStatus status, int placedSecondsAfter) {
        String orderId = UUID.randomUUID().toString();
        Instant placedAt = T0.plusSeconds(placedSecondsAfter);
        projector.on(OrderEvents.OrderPlaced.builder()
                .eventId(UUID.randomUUID().toString())
                .orderId(orderId)
                .orderNumber("LAILO-20261111-" + orderId.substring(0, 6).toUpperCase())
                .userId(userId.toString())
                .status(OrderStatus.PENDING_PAYMENT.name())
                .items(List.of(OrderEvents.Line.builder()
                        .productId(UUID.randomUUID().toString())
                        .productName("Seeded Product")
                        .productSlug("seeded-product")
                        .quantity(1)
                        .unitPrice(new BigDecimal("500.00"))
                        .lineTotal(new BigDecimal("500.00"))
                        .build()))
                .itemsTotal(new BigDecimal("500.00"))
                .shippingFee(BigDecimal.ZERO)
                .totalAmount(new BigDecimal("500.00"))
                .currency("INR")
                .paymentMethod("UPI")
                .shippingAddressLine("12 Chandni Chowk, New Delhi, Delhi 110006")
                .shippingRecipient("Aarav Mehta")
                .occurredAt(placedAt)
                .build()).block();
        if (status == OrderStatus.PENDING_PAYMENT) {
            return orderId;
        }
        if (status == OrderStatus.SHIPPED || status == OrderStatus.DELIVERED) {
            projector.on(OrderEvents.OrderShipped.builder()
                    .eventId(UUID.randomUUID().toString())
                    .orderId(orderId)
                    .orderNumber("LAILO")
                    .userId(userId.toString())
                    .courierName("Delhivery")
                    .trackingNumber("DL" + orderId.substring(0, 8))
                    .occurredAt(placedAt.plusSeconds(10))
                    .build()).block();
        }
        if (status == OrderStatus.CANCELLED) {
            projector.on(OrderEvents.OrderCancelled.builder()
                    .eventId(UUID.randomUUID().toString())
                    .orderId(orderId)
                    .orderNumber("LAILO")
                    .userId(userId.toString())
                    .reason("REQUESTED_BY_CUSTOMER")
                    .items(List.of())
                    .occurredAt(placedAt.plusSeconds(10))
                    .build()).block();
        } else if (status != OrderStatus.SHIPPED) {
            projector.on(new OrderEvents.OrderStatusChanged(UUID.randomUUID().toString(), orderId, "LAILO",
                    userId.toString(), status.name(), placedAt.plusSeconds(20))).block();
        }
        return orderId;
    }

    /**
     * The two tabs split a shopper's orders exactly along the dispatch line, and the counts match.
     * <ul>
     *   <li><b>Given</b> one shopper with a pending, a confirmed, a packed, a shipped, a delivered and a
     *       cancelled order</li>
     *   <li><b>When</b> each tab is listed</li>
     *   <li><b>Then</b> "to be shipped" holds the three warehouse orders, "shipped" holds the dispatched and
     *       delivered ones, "cancelled" holds one, and every page reports the same three counts</li>
     * </ul>
     */
    @Test
    void splitsOrdersIntoShippedAndToBeShipped() {
        UUID shopper = UUID.randomUUID();
        seedOrder(shopper, OrderStatus.PENDING_PAYMENT, 0);
        seedOrder(shopper, OrderStatus.CONFIRMED, 10);
        seedOrder(shopper, OrderStatus.PACKED, 20);
        String shipped = seedOrder(shopper, OrderStatus.SHIPPED, 30);
        seedOrder(shopper, OrderStatus.DELIVERED, 40);
        seedOrder(shopper, OrderStatus.CANCELLED, 50);

        OrderPage toBeShipped = page(shopper, OrderBucket.TO_BE_SHIPPED, 0, 10);
        OrderPage dispatched = page(shopper, OrderBucket.SHIPPED, 0, 10);
        OrderPage cancelled = page(shopper, OrderBucket.CANCELLED, 0, 10);
        OrderPage all = page(shopper, OrderBucket.ALL, 0, 10);

        assertThat(toBeShipped.total()).isEqualTo(3);
        assertThat(toBeShipped.items()).extracting(OrderView::status)
                .containsExactlyInAnyOrder("PENDING_PAYMENT", "CONFIRMED", "PACKED");
        assertThat(dispatched.total()).isEqualTo(2);
        assertThat(dispatched.items()).extracting(OrderView::status)
                .containsExactlyInAnyOrder("SHIPPED", "DELIVERED");
        assertThat(dispatched.items()).extracting(OrderView::id).contains(shipped);
        assertThat(cancelled.total()).isEqualTo(1);
        assertThat(all.total()).isEqualTo(6);

        assertThat(all.counts()).isEqualTo(new OrderCounts(3, 2, 1));
        assertThat(toBeShipped.counts()).isEqualTo(all.counts());
    }

    /**
     * A shipped order carries the details a shopper actually wants in that tab.
     * <ul>
     *   <li><b>Given</b> a shipped order</li>
     *   <li><b>When</b> the shipped tab is listed</li>
     *   <li><b>Then</b> the row reports the {@code SHIPPED} bucket, the courier, a tracking number and a
     *       dispatch time</li>
     * </ul>
     */
    @Test
    void shippedTabCarriesTrackingDetails() {
        UUID shopper = UUID.randomUUID();
        seedOrder(shopper, OrderStatus.SHIPPED, 0);

        OrderView row = page(shopper, OrderBucket.SHIPPED, 0, 10).items().getFirst();

        assertThat(row.bucket()).isEqualTo(OrderBucket.SHIPPED.name());
        assertThat(row.courierName()).isEqualTo("Delhivery");
        assertThat(row.trackingNumber()).startsWith("DL");
        assertThat(row.shippedAt()).isNotNull();
        assertThat(row.items()).hasSize(1);
    }

    /**
     * The list is newest first and pages.
     * <ul>
     *   <li><b>Given</b> three orders placed at different times</li>
     *   <li><b>When</b> the first page of two and then the second page are read</li>
     *   <li><b>Then</b> the newest order comes first, the first page reports another page follows, and the
     *       second page holds the last order and reports no more</li>
     * </ul>
     */
    @Test
    void pagesNewestFirst() {
        UUID shopper = UUID.randomUUID();
        seedOrder(shopper, OrderStatus.PENDING_PAYMENT, 0);
        seedOrder(shopper, OrderStatus.PENDING_PAYMENT, 100);
        String newest = seedOrder(shopper, OrderStatus.PENDING_PAYMENT, 200);

        OrderPage first = page(shopper, OrderBucket.ALL, 0, 2);
        OrderPage second = page(shopper, OrderBucket.ALL, 1, 2);

        assertThat(first.items()).hasSize(2);
        assertThat(first.items().getFirst().id()).isEqualTo(newest);
        assertThat(first.hasNext()).isTrue();
        assertThat(second.items()).hasSize(1);
        assertThat(second.hasNext()).isFalse();
    }

    /**
     * One shopper never sees another's orders.
     * <ul>
     *   <li><b>Given</b> two shoppers with one order each</li>
     *   <li><b>When</b> the first shopper lists their orders</li>
     *   <li><b>Then</b> only their own order is returned and the counts reflect only theirs</li>
     * </ul>
     */
    @Test
    void scopesTheListToOneShopper() {
        UUID mine = UUID.randomUUID();
        UUID theirs = UUID.randomUUID();
        String myOrder = seedOrder(mine, OrderStatus.CONFIRMED, 0);
        seedOrder(theirs, OrderStatus.CONFIRMED, 0);

        OrderPage page = page(mine, OrderBucket.ALL, 0, 10);

        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items().getFirst().id()).isEqualTo(myOrder);
        assertThat(page.counts().toBeShipped()).isEqualTo(1);
    }

    /**
     * A shopper who has never ordered gets an empty page rather than an error.
     * <ul>
     *   <li><b>Given</b> a shopper with no orders</li>
     *   <li><b>When</b> the shipped tab is listed</li>
     *   <li><b>Then</b> the page is empty, with zero counts and no next page</li>
     * </ul>
     */
    @Test
    void returnsAnEmptyPageForAShopperWithNoOrders() {
        OrderPage page = page(UUID.randomUUID(), OrderBucket.SHIPPED, 0, 10);

        assertThat(page.items()).isEmpty();
        assertThat(page.total()).isZero();
        assertThat(page.hasNext()).isFalse();
        assertThat(page.counts()).isEqualTo(new OrderCounts(0, 0, 0));
    }

    /**
     * An oversized page size is clamped instead of being served.
     * <ul>
     *   <li><b>Given</b> a request for 500 orders per page</li>
     *   <li><b>When</b> the query is built</li>
     *   <li><b>Then</b> the page size served is the 50 the query allows, so one request cannot pull a whole
     *       order history</li>
     * </ul>
     */
    @Test
    void clampsAnOversizedPageSize() {
        OrderPage page = page(UUID.randomUUID(), OrderBucket.ALL, 0, 500);

        assertThat(page.size()).isEqualTo(ListOrdersQuery.MAX_SIZE);
    }

    /**
     * Runs the list query.
     *
     * @param userId whose orders to list
     * @param bucket which tab
     * @param page zero-based page index
     * @param size page size
     * @return the page
     */
    private OrderPage page(UUID userId, OrderBucket bucket, int page, int size) {
        return queryBus.ask(ListOrdersQuery.builder()
                .userId(userId)
                .bucket(bucket)
                .page(page)
                .size(size)
                .build()).block();
    }
}
