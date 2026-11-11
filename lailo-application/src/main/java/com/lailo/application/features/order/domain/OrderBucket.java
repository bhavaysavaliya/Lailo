package com.lailo.application.features.order.domain;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The tabs the orders list is split into. The shopper asked for two of them - what has shipped and what is
 * still to be shipped - and they are defined here rather than in the query, so the API, the read model and
 * the fulfilment index all agree on where an order belongs.
 */
public enum OrderBucket {
    /** Everything still in the warehouse: awaiting payment, confirmed or packed. */
    TO_BE_SHIPPED,
    /** Everything handed to the courier, delivered included. */
    SHIPPED,
    /** Called off before dispatch. */
    CANCELLED,
    /** No filter. */
    ALL;

    /**
     * The statuses this bucket covers.
     *
     * @return the matching statuses; every status for {@code ALL}
     */
    public Set<OrderStatus> statuses() {
        return switch (this) {
            case TO_BE_SHIPPED -> Arrays.stream(OrderStatus.values())
                    .filter(OrderStatus::isAwaitingDispatch)
                    .collect(Collectors.toUnmodifiableSet());
            case SHIPPED -> Arrays.stream(OrderStatus.values())
                    .filter(OrderStatus::isDispatched)
                    .collect(Collectors.toUnmodifiableSet());
            case CANCELLED -> Set.of(OrderStatus.CANCELLED);
            case ALL -> Set.of(OrderStatus.values());
        };
    }

    /**
     * The bucket an order in this status belongs to.
     *
     * @param status the order's status
     * @return {@code TO_BE_SHIPPED}, {@code SHIPPED} or {@code CANCELLED}
     */
    public static OrderBucket of(OrderStatus status) {
        if (status.isDispatched()) {
            return SHIPPED;
        }
        return status == OrderStatus.CANCELLED ? CANCELLED : TO_BE_SHIPPED;
    }
}
