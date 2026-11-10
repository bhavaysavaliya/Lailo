package com.lailo.application.common.storage;

import lombok.Builder;

/**
 * What the object store reports about an object that exists.
 *
 * @param objectKey key in the bucket
 * @param contentType content type recorded at upload time
 * @param sizeBytes size in bytes
 * @param eTag entity tag, useful for cache validation
 */
@Builder
public record StoredObject(String objectKey, String contentType, long sizeBytes, String eTag) {
}
