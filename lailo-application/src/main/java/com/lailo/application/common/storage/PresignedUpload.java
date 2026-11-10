package com.lailo.application.common.storage;

import lombok.Builder;

import java.time.Instant;
import java.util.Map;

/**
 * A short-lived permission to upload exactly one object. The client PUTs the bytes straight to the object
 * store, so a 300 MB video never passes through this service.
 *
 * @param objectKey key the object will have in the bucket
 * @param url presigned URL; it already carries the signature as query parameters
 * @param httpMethod HTTP method the client must use, always {@code PUT} today
 * @param headers headers the client must send verbatim; they are part of the signature
 * @param expiresAt when the URL stops working
 */
@Builder
public record PresignedUpload(String objectKey, String url, String httpMethod, Map<String, String> headers,
                              Instant expiresAt) {
}
