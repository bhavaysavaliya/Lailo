package com.lailo.application.features.product.application.query;

import lombok.Builder;

/**
 * One gallery item as returned by the API.
 *
 * @param id media id
 * @param type {@code IMAGE} or {@code VIDEO}
 * @param url public URL of the bytes; stable and cacheable forever, because object keys never change
 * @param contentType stored content type, which tells the player what it is dealing with
 * @param sizeBytes stored size, useful for preloading decisions
 * @param position gallery position, ascending
 */
@Builder
public record ProductMediaView(String id, String type, String url, String contentType, long sizeBytes,
                               int position) {
}
