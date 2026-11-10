package com.lailo.application.features.product.domain;

import java.util.UUID;

/**
 * Object-key layout for product media: {@code products/{productId}/{mediaId}.{ext}}.
 *
 * <p>Keys are immutable and carry no mutable data (no name, no price, no version counter), which makes every
 * URL cacheable forever and keeps one product's media together under one prefix - so deleting a product is a
 * single prefix delete.</p>
 */
public final class MediaKeys {

    /** Not instantiable: static helpers only. */
    private MediaKeys() {
    }

    /**
     * Builds the key one media item is stored under.
     *
     * @param productId owning product
     * @param mediaId the media item's id
     * @param contentType normalised content type, which decides the extension
     * @return the object key
     */
    public static String objectKey(UUID productId, UUID mediaId, String contentType) {
        return "products/" + productId + "/" + mediaId + "." + MediaKind.extension(contentType);
    }

    /**
     * Prefix holding every media item of one product.
     *
     * @param productId owning product
     * @return the key prefix, ending with a slash
     */
    public static String productPrefix(UUID productId) {
        return "products/" + productId + "/";
    }
}
