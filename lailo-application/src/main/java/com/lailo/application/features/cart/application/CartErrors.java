package com.lailo.application.features.cart.application;

import com.lailo.application.common.web.ApiException;
import com.lailo.application.features.cart.domain.CartItem;
import org.springframework.http.HttpStatus;

import java.util.Map;
import java.util.UUID;

/** Cart error codes. Messages are written for the end user. */
public final class CartErrors {

    /** Not instantiable: static factory holder. */
    private CartErrors() {
    }

    /**
     * The cart holds no line for this product.
     *
     * @param productId the product that was looked up
     * @return 404 {@code CART_ITEM_NOT_FOUND}
     */
    public static ApiException itemNotFound(UUID productId) {
        return new ApiException(HttpStatus.NOT_FOUND, "CART_ITEM_NOT_FOUND",
                "That item isn't in your cart.", Map.of("productId", productId.toString()));
    }

    /**
     * The product has left the catalogue, so it cannot be added or ordered.
     *
     * @param productId the product
     * @return 409 {@code PRODUCT_UNAVAILABLE}
     */
    public static ApiException productUnavailable(UUID productId) {
        return new ApiException(HttpStatus.CONFLICT, "PRODUCT_UNAVAILABLE",
                "This product is no longer available.", Map.of("productId", productId.toString()));
    }

    /**
     * One line may not exceed the per-line cap, which exists to keep retail carts away from wholesale
     * quantities.
     *
     * @return 400 {@code CART_QUANTITY_LIMIT}
     */
    public static ApiException quantityLimit() {
        return new ApiException(HttpStatus.BAD_REQUEST, "CART_QUANTITY_LIMIT",
                "You can add up to " + CartItem.MAX_QUANTITY + " of one item.",
                Map.of("maxQuantity", CartItem.MAX_QUANTITY));
    }

    /**
     * There is nothing to check out.
     *
     * @return 409 {@code CART_EMPTY}
     */
    public static ApiException empty() {
        return new ApiException(HttpStatus.CONFLICT, "CART_EMPTY", "Your cart is empty.");
    }
}
