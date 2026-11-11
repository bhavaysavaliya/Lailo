package com.lailo.application.features.order.application.query;

import com.lailo.application.common.cqrs.Query;
import com.lailo.application.features.order.domain.OrderBucket;
import lombok.Builder;

import java.util.UUID;

/**
 * Lists a shopper's orders, filtered to one tab. Built with {@code ListOrdersQuery.builder()} so a caller
 * names only what it needs.
 *
 * @param userId whose orders to list
 * @param bucket which tab: {@code TO_BE_SHIPPED}, {@code SHIPPED}, {@code CANCELLED} or {@code ALL}
 * @param page zero-based page index
 * @param size page size, 1 to 50
 */
@Builder
public record ListOrdersQuery(UUID userId, OrderBucket bucket, int page, int size)
        implements Query<OrderPage> {

    /** Largest page served, so one request cannot pull a shopper's entire history. */
    public static final int MAX_SIZE = 50;

    /** Page size used when the caller does not ask for one. */
    public static final int DEFAULT_SIZE = 10;

    /** Clamps paging and defaults the tab here, so no handler or caller repeats that logic. */
    public ListOrdersQuery {
        bucket = bucket == null ? OrderBucket.ALL : bucket;
        page = Math.max(0, page);
        size = size <= 0 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
    }
}
