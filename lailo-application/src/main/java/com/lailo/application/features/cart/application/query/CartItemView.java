package com.lailo.application.features.cart.application.query;

import lombok.Builder;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One cart line as the API returns it: the stored line joined with what the catalogue currently says about
 * the product.
 *
 * <p>{@code unitPrice} is the live price and the one a checkout charges; {@code unitPriceWhenAdded} is what
 * the shopper saw when they added it, which is how the UI can say "price dropped".</p>
 *
 * @param productId the product on this line
 * @param name current product name
 * @param slug current URL segment
 * @param imageUrl first gallery image, or {@code null} when the product has no media
 * @param quantity units on the line
 * @param unitPrice live price per unit
 * @param unitPriceWhenAdded price per unit when the item was added
 * @param lineTotal {@code unitPrice} times {@code quantity}
 * @param currency ISO 4217 code
 * @param available whether the line can be ordered right now
 * @param availableQuantity units the catalogue has in stock
 * @param addedAt when the product was first added
 */
@Builder
public record CartItemView(String productId, String name, String slug, String imageUrl, int quantity,
                           BigDecimal unitPrice, BigDecimal unitPriceWhenAdded, BigDecimal lineTotal,
                           String currency, boolean available, int availableQuantity, Instant addedAt) {

    /**
     * Whether the price moved since the item was added, which the UI highlights.
     *
     * @return {@code true} when the live price differs from the remembered one
     */
    public boolean priceChanged() {
        return unitPriceWhenAdded != null && unitPrice.compareTo(unitPriceWhenAdded) != 0;
    }
}
