package com.lailo.application.features.wishlist.application.command;

import com.lailo.application.common.cqrs.Command;
import com.lailo.application.features.wishlist.application.query.WishlistView;
import com.lailo.application.features.wishlist.domain.RemoveWishlistItemReason;

import java.util.UUID;

/**
 * Unsaves a product.
 *
 * @param userId whose wishlist to change
 * @param productId product to unsave
 * @param reason why it is leaving the list
 */
public record RemoveFromWishlistCommand(UUID userId, UUID productId, RemoveWishlistItemReason reason)
        implements Command<WishlistView> {
}
