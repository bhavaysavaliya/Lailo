package com.lailo.application.features.order.application.query;

import lombok.Builder;

import java.math.BigDecimal;

/**
 * One order line as the API returns it. Every field is the value snapshotted at checkout, never a live
 * catalogue read - an order has to keep describing itself truthfully for years.
 *
 * @param productId product bought
 * @param name name at the time of the order
 * @param slug URL segment at the time of the order, for a link back to the product
 * @param imageUrl gallery image at the time of the order, or {@code null}
 * @param quantity units bought
 * @param unitPrice price charged per unit
 * @param lineTotal {@code unitPrice} times {@code quantity}
 * @param currency ISO 4217 code
 */
@Builder
public record OrderItemView(String productId, String name, String slug, String imageUrl, int quantity,
                            BigDecimal unitPrice, BigDecimal lineTotal, String currency) {
}
