package com.lailo.application.features.wishlist.domain;

/** Why a saved product left the wishlist; carried on {@code WishlistItemRemoved}. */
public enum RemoveWishlistItemReason {
    /** The shopper unsaved it. */
    REMOVED_BY_USER,
    /** It became a cart line, so keeping it saved would duplicate it. */
    MOVED_TO_CART
}
