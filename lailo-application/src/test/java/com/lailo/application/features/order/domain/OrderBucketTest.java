package com.lailo.application.features.order.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the rule that decides which tab an order appears in. It is the whole contract behind
 * "shipped" and "to be shipped", so it is pinned down here rather than inferred from a query test.
 */
class OrderBucketTest {

    /**
     * Every status maps to exactly the tab a shopper would expect.
     * <ul>
     *   <li><b>Given</b> each order status</li>
     *   <li><b>When</b> its bucket is derived</li>
     *   <li><b>Then</b> awaiting-dispatch statuses are {@code TO_BE_SHIPPED}, a dispatched or delivered
     *       order is {@code SHIPPED}, and a cancelled one is {@code CANCELLED}</li>
     * </ul>
     *
     * @param status the order status
     * @param expected the tab it belongs to
     */
    @ParameterizedTest
    @CsvSource({
            "PENDING_PAYMENT, TO_BE_SHIPPED",
            "CONFIRMED,       TO_BE_SHIPPED",
            "PACKED,          TO_BE_SHIPPED",
            "SHIPPED,         SHIPPED",
            "DELIVERED,       SHIPPED",
            "CANCELLED,       CANCELLED"
    })
    void mapsEveryStatusToATab(OrderStatus status, OrderBucket expected) {
        assertThat(OrderBucket.of(status)).isEqualTo(expected);
    }

    /**
     * A delivered order stays in the shipped tab instead of vanishing.
     * <ul>
     *   <li><b>Given</b> the {@code SHIPPED} bucket</li>
     *   <li><b>When</b> its statuses are listed</li>
     *   <li><b>Then</b> they are exactly {@code SHIPPED} and {@code DELIVERED}</li>
     * </ul>
     */
    @Test
    void shippedTabKeepsDeliveredOrders() {
        assertThat(OrderBucket.SHIPPED.statuses())
                .containsExactlyInAnyOrder(OrderStatus.SHIPPED, OrderStatus.DELIVERED);
    }

    /**
     * The to-be-shipped tab covers everything still in the warehouse and nothing else.
     * <ul>
     *   <li><b>Given</b> the {@code TO_BE_SHIPPED} bucket</li>
     *   <li><b>When</b> its statuses are listed</li>
     *   <li><b>Then</b> they are the three pre-dispatch statuses, with no cancelled or shipped order
     *       among them</li>
     * </ul>
     */
    @Test
    void toBeShippedTabCoversEverythingStillInTheWarehouse() {
        assertThat(OrderBucket.TO_BE_SHIPPED.statuses())
                .containsExactlyInAnyOrder(OrderStatus.PENDING_PAYMENT, OrderStatus.CONFIRMED,
                        OrderStatus.PACKED)
                .doesNotContain(OrderStatus.SHIPPED, OrderStatus.CANCELLED);
    }

    /**
     * The unfiltered tab really is unfiltered.
     * <ul>
     *   <li><b>Given</b> the {@code ALL} bucket</li>
     *   <li><b>When</b> its statuses are listed</li>
     *   <li><b>Then</b> every status is included</li>
     * </ul>
     */
    @Test
    void allTabCoversEveryStatus() {
        assertThat(OrderBucket.ALL.statuses()).containsExactlyInAnyOrder(OrderStatus.values());
    }

    /**
     * Cancellation is allowed up to dispatch and not after.
     * <ul>
     *   <li><b>Given</b> each order status</li>
     *   <li><b>When</b> it is asked whether the order can still be cancelled</li>
     *   <li><b>Then</b> only the pre-dispatch statuses say yes</li>
     * </ul>
     */
    @Test
    void cancellationIsAllowedOnlyBeforeDispatch() {
        assertThat(OrderStatus.PENDING_PAYMENT.isCancellable()).isTrue();
        assertThat(OrderStatus.CONFIRMED.isCancellable()).isTrue();
        assertThat(OrderStatus.PACKED.isCancellable()).isTrue();
        assertThat(OrderStatus.SHIPPED.isCancellable()).isFalse();
        assertThat(OrderStatus.DELIVERED.isCancellable()).isFalse();
        assertThat(OrderStatus.CANCELLED.isCancellable()).isFalse();
    }
}
