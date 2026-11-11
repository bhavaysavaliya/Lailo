package com.lailo.application.features.wishlist.application.query;

import com.lailo.application.common.cqrs.Query;

import java.util.UUID;

/**
 * Reads one shopper's wishlist.
 *
 * @param userId whose wishlist to read
 */
public record GetWishlistQuery(UUID userId) implements Query<WishlistView> {
}
