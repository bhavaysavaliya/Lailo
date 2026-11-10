package com.lailo.application.common.storage;

import reactor.core.publisher.Mono;

import java.time.Duration;

/** Blob storage behind an S3-compatible API. Implemented by {@link S3ObjectStorage} against SeaweedFS. */
public interface ObjectStorage {

    /**
     * Grants a client permission to upload one object. Signing happens locally, so this performs no I/O.
     *
     * <p>The content type and length are part of the signature: a client that sends different ones is
     * rejected by the store, which is what keeps the declared limits honest.</p>
     *
     * @param objectKey key the object must be stored under
     * @param contentType exact content type the client must send
     * @param contentLength exact number of bytes the client must send
     * @param ttl how long the URL stays valid
     * @return the presigned upload
     */
    PresignedUpload presignUpload(String objectKey, String contentType, long contentLength, Duration ttl);

    /**
     * Reads an object's metadata without downloading it.
     *
     * @param objectKey key in the bucket
     * @return the object, or empty when it does not exist
     */
    Mono<StoredObject> head(String objectKey);

    /**
     * Deletes an object. Succeeds when the object is already gone.
     *
     * @param objectKey key in the bucket
     * @return completion signal
     */
    Mono<Void> delete(String objectKey);

    /**
     * Builds the URL a browser uses to fetch an object. Keys are immutable, so these URLs can be cached
     * indefinitely by a CDN.
     *
     * @param objectKey key in the bucket
     * @return the public URL
     */
    String publicUrl(String objectKey);
}
