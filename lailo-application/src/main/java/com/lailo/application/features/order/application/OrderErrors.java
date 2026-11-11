package com.lailo.application.features.order.application;

import com.lailo.application.common.web.ApiException;
import org.springframework.http.HttpStatus;

import java.util.Map;
import java.util.UUID;

/** Order error codes. Messages are written for the end user. */
public final class OrderErrors {

    /** Not instantiable: static factory holder. */
    private OrderErrors() {
    }

    /**
     * No such order, or it belongs to another shopper - answered identically so order ids cannot be probed.
     *
     * @param orderId the id that was looked up
     * @return 404 {@code ORDER_NOT_FOUND}
     */
    public static ApiException notFound(UUID orderId) {
        return new ApiException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND",
                "We couldn't find that order.", Map.of("orderId", orderId.toString()));
    }

    /**
     * Something in the cart cannot be ordered, so checkout stops rather than quietly dropping a line.
     *
     * @return 409 {@code CART_HAS_UNAVAILABLE_ITEMS}
     */
    public static ApiException cartHasUnavailableItems() {
        return new ApiException(HttpStatus.CONFLICT, "CART_HAS_UNAVAILABLE_ITEMS",
                "Some items in your cart are no longer available. Remove them to continue.");
    }

    /**
     * Cash on delivery is switched off.
     *
     * @return 409 {@code COD_UNAVAILABLE}
     */
    public static ApiException codUnavailable() {
        return new ApiException(HttpStatus.CONFLICT, "COD_UNAVAILABLE",
                "Cash on delivery isn't available right now. Choose another payment method.");
    }

    /**
     * The order has moved past the point where this transition is legal.
     *
     * @param status the status it is in
     * @param wanted what was attempted
     * @return 409 {@code ORDER_STATUS_INVALID}
     */
    public static ApiException invalidTransition(String status, String wanted) {
        return new ApiException(HttpStatus.CONFLICT, "ORDER_STATUS_INVALID",
                "This order is " + status.toLowerCase().replace('_', ' ') + ", so it can't be " + wanted + ".",
                Map.of("status", status));
    }

    /**
     * The parcel is already with the courier, so it is a return rather than a cancellation.
     *
     * @param status the status it is in
     * @return 409 {@code ORDER_NOT_CANCELLABLE}
     */
    public static ApiException notCancellable(String status) {
        return new ApiException(HttpStatus.CONFLICT, "ORDER_NOT_CANCELLABLE",
                "This order has already been dispatched and can no longer be cancelled.",
                Map.of("status", status));
    }
}
