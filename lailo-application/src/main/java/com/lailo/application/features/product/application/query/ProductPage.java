package com.lailo.application.features.product.application.query;

import lombok.Builder;

import java.util.List;

/**
 * One page of catalogue results.
 *
 * @param items the products on this page
 * @param page zero-based page index
 * @param size page size that was applied
 * @param total number of products matching the filters
 * @param hasNext whether another page follows
 */
@Builder
public record ProductPage(List<ProductView> items, int page, int size, long total, boolean hasNext) {
}
