package com.lailo.application.features.wishlist.domain.event;

import java.time.Instant;

/**
 * Integration events for the wishlist, published through the outbox.
 *
 * <p>They are the signal behind "back in stock" and "price dropped" notifications: those consumers need to
 * know who is waiting for which product, and the only trustworthy source of that is this stream.</p>
 */
public final class WishlistEvents {

    public static final String TOPIC = "lailo.wishlist.wishlist-events.v1";
    public static final String AGGREGATE = "Wishlist";

    public static final String WISHLIST_ITEM_ADDED = "WishlistItemAdded";
    public static final String WISHLIST_ITEM_REMOVED = "WishlistItemRemoved";

    /** Not instantiable: constants and event types only. */
    private WishlistEvents() {
    }

    /**
     * A product was saved for later.
     *
     * @param eventId unique event id, used for idempotency
     * @param userId who saved it
     * @param productId product saved
     * @param occurredAt time of saving
     */
    public record WishlistItemAdded(String eventId, String userId, String productId, Instant occurredAt) {
    }

    /**
     * A saved product was removed, either directly or because it moved into the cart.
     *
     * @param eventId unique event id
     * @param userId whose wishlist changed
     * @param productId product removed
     * @param reason {@code REMOVED_BY_USER} or {@code MOVED_TO_CART}
     * @param occurredAt removal time
     */
    public record WishlistItemRemoved(String eventId, String userId, String productId, String reason,
                                      Instant occurredAt) {
    }
}
