package com.lailo.application.features.product.domain;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Accepted media types and the object-key layout they are stored under. */
class MediaKeysTest {

    private static final UUID PRODUCT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID MEDIA = UUID.fromString("22222222-2222-4222-8222-222222222222");

    /**
     * Keys group a product's media under one prefix and end with the right extension.
     * <ul>
     *   <li><b>Given</b> a product id, a media id and a WebP content type</li>
     *   <li><b>When</b> the object key is built</li>
     *   <li><b>Then</b> it is {@code products/{productId}/{mediaId}.webp} and starts with the product prefix</li>
     * </ul>
     */
    @Test
    void buildsKeysUnderAPerProductPrefix() {
        String key = MediaKeys.objectKey(PRODUCT, MEDIA, "image/webp");

        assertThat(key).isEqualTo("products/" + PRODUCT + "/" + MEDIA + ".webp");
        assertThat(key).startsWith(MediaKeys.productPrefix(PRODUCT));
    }

    /**
     * Video content types map to the video kind, images to the image kind.
     * <ul>
     *   <li><b>Given</b> the accepted content types</li>
     *   <li><b>When</b> each is classified</li>
     *   <li><b>Then</b> MP4 and WebM are videos and JPEG, PNG, WebP and AVIF are images</li>
     * </ul>
     */
    @Test
    void classifiesImagesAndVideos() {
        assertThat(MediaKind.typeOf("video/mp4")).contains(MediaType.VIDEO);
        assertThat(MediaKind.typeOf("video/webm")).contains(MediaType.VIDEO);
        assertThat(MediaKind.typeOf("image/jpeg")).contains(MediaType.IMAGE);
        assertThat(MediaKind.typeOf("image/png")).contains(MediaType.IMAGE);
        assertThat(MediaKind.typeOf("image/webp")).contains(MediaType.IMAGE);
        assertThat(MediaKind.typeOf("image/avif")).contains(MediaType.IMAGE);
    }

    /**
     * Anything outside the allow-list is rejected, with charset parameters and casing ignored.
     * <ul>
     *   <li><b>Given</b> an SVG type, a PDF type and {@code IMAGE/PNG; charset=utf-8}</li>
     *   <li><b>When</b> each is normalised and classified</li>
     *   <li><b>Then</b> SVG and PDF are not accepted while the PNG is, because normalisation strips the
     *       parameter and lower-cases the type</li>
     * </ul>
     */
    @Test
    void rejectsTypesOutsideTheAllowList() {
        assertThat(MediaKind.typeOf(MediaKind.normalise("image/svg+xml"))).isEmpty();
        assertThat(MediaKind.typeOf(MediaKind.normalise("application/pdf"))).isEmpty();
        assertThat(MediaKind.typeOf(MediaKind.normalise(null))).isEqualTo(Optional.empty());
        assertThat(MediaKind.typeOf(MediaKind.normalise("IMAGE/PNG; charset=utf-8"))).contains(MediaType.IMAGE);
    }
}
