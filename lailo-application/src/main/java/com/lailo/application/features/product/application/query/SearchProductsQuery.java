package com.lailo.application.features.product.application.query;

import com.lailo.application.common.cqrs.Query;
import lombok.Builder;

import java.math.BigDecimal;

/**
 * Catalogue browse and search. Built with {@code SearchProductsQuery.builder()}, so a caller names only the
 * filters it wants: every filter is optional, and archived products are never returned.
 *
 * @param text free text matched against name, brand, category and description, or {@code null}
 * @param category exact category (case-insensitive), or {@code null}
 * @param minPrice lowest acceptable price, or {@code null}
 * @param maxPrice highest acceptable price, or {@code null}
 * @param inStockOnly when true, only products with stock left
 * @param sort ordering; defaults to {@code NEWEST}
 * @param page zero-based page index
 * @param size page size, 1 to 100
 */
@Builder
public record SearchProductsQuery(String text, String category, BigDecimal minPrice, BigDecimal maxPrice,
                                  boolean inStockOnly, ProductSort sort, int page, int size)
        implements Query<ProductPage> {

    /** Largest page the catalogue will serve, so one request cannot pull the whole collection. */
    public static final int MAX_SIZE = 100;

    /** Page size used when the caller does not ask for one. */
    public static final int DEFAULT_SIZE = 20;

    /** Clamps paging and fills defaults here, so no handler or caller has to repeat that logic. */
    public SearchProductsQuery {
        text = blankToNull(text);
        category = blankToNull(category);
        sort = sort == null ? ProductSort.NEWEST : sort;
        page = Math.max(0, page);
        size = size <= 0 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
    }

    /**
     * Treats a blank filter as absent.
     *
     * @param value raw filter value
     * @return the trimmed value, or {@code null}
     */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
