package com.lailo.application.features.wishlist.application.query;

import lombok.Builder;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One saved product as the API returns it: the stored entry joined with live catalogue detail, so a shopper
 * sees today's price and today's availability rather than whatever was true when they saved it.
 *
 * @param productId the saved product
 * @param name current product name
 * @param slug current URL segment
 * @param imageUrl first gallery image, or {@code null}
 * @param price live price per unit
 * @param currency ISO 4217 code
 * @param inStock whether the product can be bought right now
 * @param available whether the product is still in the catalogue at all
 * @param note the shopper's own note, or {@code null}
 * @param savedAt when it was saved
 */
@Builder
public record WishlistItemView(String productId, String name, String slug, String imageUrl, BigDecimal price,
                               String currency, boolean inStock, boolean available, String note,
                               Instant savedAt) {
}
