package com.lailo.application.features.order.application.query;

import lombok.Builder;

import java.util.List;

/**
 * One page of a shopper's orders.
 *
 * @param items the orders on this page, newest first
 * @param bucket which tab was asked for
 * @param page zero-based page index
 * @param size page size that was applied
 * @param total orders in this bucket
 * @param hasNext whether another page follows
 * @param counts totals for every tab
 */
@Builder
public record OrderPage(List<OrderView> items, String bucket, int page, int size, long total, boolean hasNext,
                        OrderCounts counts) {
}
