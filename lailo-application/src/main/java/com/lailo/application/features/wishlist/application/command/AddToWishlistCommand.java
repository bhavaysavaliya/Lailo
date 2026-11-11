package com.lailo.application.features.wishlist.application.command;

import com.lailo.application.common.cqrs.Command;
import com.lailo.application.features.wishlist.application.query.WishlistView;

import java.util.UUID;

/**
 * Saves a product for later. Idempotent: saving the same product again only updates the note.
 *
 * @param userId whose wishlist to change
 * @param productId product to save
 * @param note optional note from the shopper
 */
public record AddToWishlistCommand(UUID userId, UUID productId, String note) implements Command<WishlistView> {
}
