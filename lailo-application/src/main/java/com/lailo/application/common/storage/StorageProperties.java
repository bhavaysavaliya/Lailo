package com.lailo.application.common.storage;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Object-store settings. The store is S3-compatible: SeaweedFS locally and in production, or AWS S3 / any
 * other S3 API without a code change - only these values differ.
 *
 * @param endpoint base URL of the S3 API, e.g. {@code http://localhost:8333}
 * @param region signing region; any value works with SeaweedFS as long as it is stable
 * @param bucket bucket holding product media
 * @param accessKey access key id; required, like every other secret
 * @param secretKey secret access key; required, so a misconfigured deployment fails at startup rather than
 *        at the first upload
 * @param publicBaseUrl base URL browsers use to fetch objects, without a trailing slash; usually the CDN in
 *        front of the store, and {@code endpoint/bucket} when there is none
 * @param pathStyleAccess {@code true} for SeaweedFS and MinIO, which do not support virtual-host buckets
 * @param uploadUrlTtl how long a presigned upload URL stays valid
 * @param createBucketIfMissing create the bucket at startup; convenient in development, off by default
 */
@Validated
@ConfigurationProperties(prefix = "lailo.storage")
public record StorageProperties(
        @NotBlank String endpoint,
        @DefaultValue("us-east-1") @NotBlank String region,
        @DefaultValue("lailo-media") @NotBlank String bucket,
        @NotBlank String accessKey,
        @NotBlank String secretKey,
        @NotBlank String publicBaseUrl,
        @DefaultValue("true") boolean pathStyleAccess,
        @DefaultValue("10m") Duration uploadUrlTtl,
        @DefaultValue("false") boolean createBucketIfMissing) {
}
