package com.lailo.application.features.wishlist.application.query;

import lombok.Builder;

import java.util.List;

/**
 * A shopper's whole wishlist. It is returned by every wishlist write for the same reason the cart is: the
 * client renders one component and needs no follow-up read.
 *
 * @param userId whose wishlist this is
 * @param items saved products, most recent first
 * @param itemCount number of saved products
 */
@Builder
public record WishlistView(String userId, List<WishlistItemView> items, int itemCount) {
}
