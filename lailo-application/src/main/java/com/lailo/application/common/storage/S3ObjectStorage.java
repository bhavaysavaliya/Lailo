package com.lailo.application.common.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;

/**
 * {@link ObjectStorage} on the S3 API. Metadata calls use the async client so no request thread blocks;
 * presigning is pure local signing and returns immediately.
 */
@Slf4j
@Component
public class S3ObjectStorage implements ObjectStorage {

    private static final int NOT_FOUND = 404;

    private final S3AsyncClient client;
    private final S3Presigner presigner;
    private final String bucket;
    private final String publicBaseUrl;

    /**
     * Creates the storage adapter.
     *
     * @param client async S3 client pointed at the configured endpoint
     * @param presigner presigner sharing the same credentials and endpoint
     * @param properties bucket and public base URL
     */
    public S3ObjectStorage(S3AsyncClient client, S3Presigner presigner, StorageProperties properties) {
        this.client = client;
        this.presigner = presigner;
        this.bucket = properties.bucket();
        this.publicBaseUrl = properties.publicBaseUrl().replaceAll("/+$", "");
    }

    /** {@inheritDoc} */
    @Override
    public PresignedUpload presignUpload(String objectKey, String contentType, long contentLength, Duration ttl) {
        PutObjectRequest put = PutObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                .contentType(contentType)
                .contentLength(contentLength)
                .build();
        var presigned = presigner.presignPutObject(PutObjectPresignRequest.builder()
                .signatureDuration(ttl)
                .putObjectRequest(put)
                .build());
        return PresignedUpload.builder()
                .objectKey(objectKey)
                .url(presigned.url().toString())
                .httpMethod(presigned.httpRequest().method().name())
                // Signed headers must be echoed exactly, or the store answers 403.
                .headers(Map.of("Content-Type", contentType, "Content-Length", Long.toString(contentLength)))
                .expiresAt(presigned.expiration())
                .build();
    }

    /** {@inheritDoc} */
    @Override
    public Mono<StoredObject> head(String objectKey) {
        return Mono.fromFuture(() -> client.headObject(HeadObjectRequest.builder()
                        .bucket(bucket).key(objectKey).build()))
                .map(res -> StoredObject.builder()
                        .objectKey(objectKey)
                        .contentType(res.contentType())
                        .sizeBytes(res.contentLength() == null ? 0L : res.contentLength())
                        .eTag(res.eTag())
                        .build())
                .onErrorResume(S3ObjectStorage::isMissing, ex -> Mono.empty());
    }

    /** {@inheritDoc} */
    @Override
    public Mono<Void> delete(String objectKey) {
        return Mono.fromFuture(() -> client.deleteObject(DeleteObjectRequest.builder()
                        .bucket(bucket).key(objectKey).build()))
                .then();
    }

    /** {@inheritDoc} */
    @Override
    public String publicUrl(String objectKey) {
        String encoded = Arrays.stream(objectKey.split("/", -1))
                .map(segment -> URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20"))
                .reduce((a, b) -> a + "/" + b)
                .orElse("");
        return publicBaseUrl + "/" + encoded;
    }

    /**
     * Whether a failure means "the object is not there" rather than a real problem. {@code HeadObject} has no
     * response body, so some stores surface the 404 as a generic {@code S3Exception}.
     *
     * @param ex the failure
     * @return {@code true} when the object does not exist
     */
    private static boolean isMissing(Throwable ex) {
        if (ex instanceof NoSuchKeyException) {
            return true;
        }
        return ex instanceof S3Exception s3 && s3.statusCode() == NOT_FOUND;
    }
}
