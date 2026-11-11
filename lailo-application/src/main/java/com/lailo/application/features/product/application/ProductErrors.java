package com.lailo.application.features.product.application;

import com.lailo.application.common.web.ApiException;
import org.springframework.http.HttpStatus;

import java.util.UUID;

/** Catalogue error codes. Messages are written for the end user. */
public final class ProductErrors {

    /** Not instantiable: static factory holder. */
    private ProductErrors() {
    }

    /**
     * No product with this id exists, or it is not in the catalogue.
     *
     * @param productId the id that was looked up
     * @return 404 {@code PRODUCT_NOT_FOUND}
     */
    public static ApiException notFound(UUID productId) {
        return new ApiException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND",
                "We couldn't find that product.", java.util.Map.of("productId", productId.toString()));
    }

    /**
     * The product has been withdrawn, so its details can no longer be edited.
     *
     * @return 409 {@code PRODUCT_ARCHIVED}
     */
    public static ApiException archived() {
        return new ApiException(HttpStatus.CONFLICT, "PRODUCT_ARCHIVED",
                "This product has been removed from the catalogue.");
    }

    /**
     * Fewer units are available than were asked for. The available count is returned so the caller can offer
     * "add the 2 we have left" instead of a dead end.
     *
     * @param productId the product that ran short
     * @param requested units asked for
     * @param available units actually in stock
     * @return 409 {@code PRODUCT_OUT_OF_STOCK}
     */
    public static ApiException outOfStock(UUID productId, int requested, int available) {
        return new ApiException(HttpStatus.CONFLICT, "PRODUCT_OUT_OF_STOCK",
                available == 0
                        ? "This product just went out of stock."
                        : "Only " + available + " left in stock, so " + requested + " can't be reserved.",
                java.util.Map.of("productId", productId.toString(), "requested", requested, "available", available));
    }

    /**
     * The content type is not one Lailo stores.
     *
     * @param contentType what the client declared
     * @return 415 {@code MEDIA_TYPE_UNSUPPORTED}
     */
    public static ApiException mediaTypeUnsupported(String contentType) {
        return new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "MEDIA_TYPE_UNSUPPORTED",
                "That file type isn't supported. Use JPEG, PNG, WebP or AVIF images, or MP4 or WebM video.",
                java.util.Map.of("contentType", contentType == null ? "" : contentType));
    }

    /**
     * The declared upload is larger than the limit for its kind.
     *
     * @param maxBytes the limit that applies
     * @return 413 {@code MEDIA_TOO_LARGE}
     */
    public static ApiException mediaTooLarge(long maxBytes) {
        return new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "MEDIA_TOO_LARGE",
                "That file is too large. The limit is " + maxBytes / (1024 * 1024) + " MB.",
                java.util.Map.of("maxBytes", maxBytes));
    }

    /**
     * The product already holds as many media items as it may.
     *
     * @param maxPerProduct the cap
     * @return 409 {@code MEDIA_LIMIT_REACHED}
     */
    public static ApiException mediaLimitReached(int maxPerProduct) {
        return new ApiException(HttpStatus.CONFLICT, "MEDIA_LIMIT_REACHED",
                "This product already has " + maxPerProduct + " images or videos. Remove one first.",
                java.util.Map.of("maxPerProduct", maxPerProduct));
    }

    /**
     * No such media item on this product.
     *
     * @param mediaId the id that was looked up
     * @return 404 {@code MEDIA_NOT_FOUND}
     */
    public static ApiException mediaNotFound(UUID mediaId) {
        return new ApiException(HttpStatus.NOT_FOUND, "MEDIA_NOT_FOUND",
                "We couldn't find that image or video.", java.util.Map.of("mediaId", mediaId.toString()));
    }

    /**
     * The upload was never finished: the object store has no bytes under the reserved key.
     *
     * @return 409 {@code MEDIA_UPLOAD_INCOMPLETE}
     */
    public static ApiException mediaUploadIncomplete() {
        return new ApiException(HttpStatus.CONFLICT, "MEDIA_UPLOAD_INCOMPLETE",
                "The upload didn't finish. Upload the file again, then confirm it.");
    }

    /**
     * The stored object does not match what was reserved, so it is not published.
     *
     * @param expected content type that was reserved
     * @param actual content type the store reports
     * @return 409 {@code MEDIA_CONTENT_MISMATCH}
     */
    public static ApiException mediaContentMismatch(String expected, String actual) {
        return new ApiException(HttpStatus.CONFLICT, "MEDIA_CONTENT_MISMATCH",
                "The uploaded file doesn't match what was requested. Start the upload again.",
                java.util.Map.of("expectedContentType", expected, "actualContentType", actual == null ? "" : actual));
    }
}
