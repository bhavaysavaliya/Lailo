package com.lailo.application.features.order.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * What an order costs, computed once at checkout and then frozen on the order.
 *
 * @param itemsTotal sum of the lines
 * @param shippingFee delivery charge applied
 * @param totalAmount what the shopper is charged
 * @param currency ISO 4217 code
 */
public record OrderTotals(BigDecimal itemsTotal, BigDecimal shippingFee, BigDecimal totalAmount,
                          String currency) {

    /**
     * Scales every amount to two decimals and checks they add up, so a rounding mistake can never be stored
     * and then charged.
     */
    public OrderTotals {
        itemsTotal = scale(itemsTotal, "itemsTotal");
        shippingFee = scale(shippingFee, "shippingFee");
        totalAmount = scale(totalAmount, "totalAmount");
        if (currency == null || currency.isBlank()) {
            throw new IllegalArgumentException("currency is required");
        }
        if (itemsTotal.signum() <= 0) {
            throw new IllegalArgumentException("itemsTotal must be greater than zero");
        }
        if (itemsTotal.add(shippingFee).compareTo(totalAmount) != 0) {
            throw new IllegalArgumentException("totalAmount must equal itemsTotal plus shippingFee");
        }
    }

    /**
     * Builds the totals from the lines and the delivery charge.
     *
     * @param itemsTotal sum of the lines
     * @param shippingFee delivery charge
     * @param currency ISO 4217 code
     * @return the totals, with {@code totalAmount} derived
     */
    public static OrderTotals of(BigDecimal itemsTotal, BigDecimal shippingFee, String currency) {
        BigDecimal items = scale(itemsTotal, "itemsTotal");
        BigDecimal shipping = scale(shippingFee, "shippingFee");
        return new OrderTotals(items, shipping, items.add(shipping), currency);
    }

    /**
     * Rounds an amount to the two decimals every column and gateway uses.
     *
     * @param amount the amount
     * @param field field name for the message
     * @return the scaled amount
     */
    private static BigDecimal scale(BigDecimal amount, String field) {
        if (amount == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        if (amount.signum() < 0) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
        return amount.setScale(2, RoundingMode.HALF_UP);
    }
}
