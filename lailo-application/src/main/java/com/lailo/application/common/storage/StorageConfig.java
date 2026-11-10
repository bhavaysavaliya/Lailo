package com.lailo.application.common.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** Wires the S3 client and presigner for the configured endpoint (SeaweedFS by default). */
@Slf4j
@Configuration
@EnableConfigurationProperties(StorageProperties.class)
public class StorageConfig {

    private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(10);

    /**
     * Builds the async S3 client. Path-style addressing is required by SeaweedFS and MinIO, which serve every
     * bucket from one host instead of a per-bucket subdomain.
     *
     * @param props endpoint, region, credentials
     * @return the client, closed with the application context
     */
    @Bean(destroyMethod = "close")
    S3AsyncClient s3AsyncClient(StorageProperties props) {
        return S3AsyncClient.builder()
                .endpointOverride(URI.create(props.endpoint()))
                .region(Region.of(props.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(props.accessKey(), props.secretKey())))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(props.pathStyleAccess())
                        .chunkedEncodingEnabled(false)
                        .build())
                .build();
    }

    /**
     * Builds the presigner. It signs locally and never calls the store, so it needs no connection pool.
     *
     * @param props endpoint, region, credentials
     * @return the presigner, closed with the application context
     */
    @Bean(destroyMethod = "close")
    S3Presigner s3Presigner(StorageProperties props) {
        return S3Presigner.builder()
                .endpointOverride(URI.create(props.endpoint()))
                .region(Region.of(props.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(props.accessKey(), props.secretKey())))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(props.pathStyleAccess())
                        .build())
                .build();
    }

    /**
     * Ensures the media bucket exists when {@code lailo.storage.create-bucket-if-missing} is on, so a fresh
     * development SeaweedFS needs no manual setup. Production grants no bucket-creation rights, so the default
     * is off and a failure is logged instead of stopping startup.
     *
     * @param client the S3 client
     * @param props bucket name and the toggle
     * @return a marker bean whose creation performed the check
     */
    @Bean
    BucketReady bucketReady(S3AsyncClient client, StorageProperties props) {
        if (props.createBucketIfMissing()) {
            ensureBucket(client, props.bucket());
        }
        return new BucketReady();
    }

    /**
     * Creates the bucket if a {@code HeadBucket} says it is missing, tolerating a race with another instance.
     *
     * @param client the S3 client
     * @param bucket bucket name
     */
    private static void ensureBucket(S3AsyncClient client, String bucket) {
        try {
            client.headBucket(HeadBucketRequest.builder().bucket(bucket).build())
                    .toCompletableFuture().get(STARTUP_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception missing) {
            try {
                client.createBucket(CreateBucketRequest.builder().bucket(bucket).build())
                        .toCompletableFuture().get(STARTUP_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
                log.info("Created object-storage bucket {}", bucket);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception ex) {
                if (!(ex.getCause() instanceof BucketAlreadyOwnedByYouException)) {
                    log.warn("Could not create bucket {}: {}", bucket, ex.getMessage());
                }
            }
        }
    }

    /** Marker that the bucket check has run; beans needing the bucket can depend on it. */
    public static final class BucketReady {

        /** Nothing to hold: the work happens in {@link StorageConfig#bucketReady}. */
        BucketReady() {
        }
    }
}
