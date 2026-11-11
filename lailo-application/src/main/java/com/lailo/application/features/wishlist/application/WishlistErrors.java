package com.lailo.application.features.wishlist.application;

import com.lailo.application.common.web.ApiException;
import org.springframework.http.HttpStatus;

import java.util.Map;
import java.util.UUID;

/** Wishlist error codes. Messages are written for the end user. */
public final class WishlistErrors {

    /** Not instantiable: static factory holder. */
    private WishlistErrors() {
    }

    /**
     * The product is not on this shopper's wishlist.
     *
     * @param productId the product that was looked up
     * @return 404 {@code WISHLIST_ITEM_NOT_FOUND}
     */
    public static ApiException itemNotFound(UUID productId) {
        return new ApiException(HttpStatus.NOT_FOUND, "WISHLIST_ITEM_NOT_FOUND",
                "That item isn't in your wishlist.", Map.of("productId", productId.toString()));
    }
}
