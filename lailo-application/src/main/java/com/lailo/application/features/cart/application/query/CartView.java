package com.lailo.application.features.cart.application.query;

import lombok.Builder;

import java.math.BigDecimal;
import java.util.List;

/**
 * A shopper's whole cart. Every command that changes the cart returns this, so the client never has to
 * follow a write with a read.
 *
 * @param userId whose cart this is
 * @param items the lines, oldest first
 * @param itemCount number of lines
 * @param totalQuantity units across all lines
 * @param itemsTotal sum of the orderable lines at live prices
 * @param currency ISO 4217 code
 * @param hasUnavailableItems whether something in the cart cannot be ordered, which blocks checkout
 */
@Builder
public record CartView(String userId, List<CartItemView> items, int itemCount, int totalQuantity,
                       BigDecimal itemsTotal, String currency, boolean hasUnavailableItems) {

    /**
     * Whether there is nothing to check out.
     *
     * @return {@code true} when the cart holds no lines
     */
    public boolean isEmpty() {
        return items.isEmpty();
    }
}
