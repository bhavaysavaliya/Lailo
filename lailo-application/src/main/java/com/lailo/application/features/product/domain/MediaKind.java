package com.lailo.application.features.product.domain;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The content types Lailo accepts for product media, and the file extension each one is stored with.
 * Everything else is rejected before a presigned URL is handed out, so the bucket can only ever hold
 * formats a browser can play or display.
 */
public final class MediaKind {

    private static final Map<String, String> EXTENSIONS = Map.ofEntries(
            Map.entry("image/jpeg", "jpg"),
            Map.entry("image/png", "png"),
            Map.entry("image/webp", "webp"),
            Map.entry("image/avif", "avif"),
            Map.entry("video/mp4", "mp4"),
            Map.entry("video/webm", "webm"));

    /** Not instantiable: static helpers only. */
    private MediaKind() {
    }

    /**
     * Normalises a content type: lower-cased and without any {@code ; charset=...} parameters.
     *
     * @param contentType content type as sent by the client, may be {@code null}
     * @return the bare type, or an empty string
     */
    public static String normalise(String contentType) {
        if (contentType == null) {
            return "";
        }
        return contentType.split(";")[0].trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Maps a content type to the media kind it represents.
     *
     * @param contentType normalised content type
     * @return {@code IMAGE} or {@code VIDEO}, or empty when the type is not accepted
     */
    public static Optional<MediaType> typeOf(String contentType) {
        if (!EXTENSIONS.containsKey(contentType)) {
            return Optional.empty();
        }
        return Optional.of(contentType.startsWith("video/") ? MediaType.VIDEO : MediaType.IMAGE);
    }

    /**
     * File extension an accepted content type is stored with.
     *
     * @param contentType normalised content type
     * @return the extension without a dot, or {@code bin} for an unknown type
     */
    public static String extension(String contentType) {
        return EXTENSIONS.getOrDefault(contentType, "bin");
    }
}
