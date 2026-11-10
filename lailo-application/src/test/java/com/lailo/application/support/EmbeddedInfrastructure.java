package com.lailo.application.support;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import redis.embedded.RedisServer;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;

/**
 * Real PostgreSQL and Redis processes started once per test JVM - no Docker required.
 * MongoDB comes from flapdoodle's auto-configuration ({@code de.flapdoodle.mongodb.embedded.version} in
 * application-test.yaml) and Kafka from {@code @EmbeddedKafka} on {@link IntegrationTest}.
 *
 * <p>The S3-compatible object store (SeaweedFS in production) is the one dependency that is stubbed rather
 * than embedded: it is reached purely over HTTP, so WireMock stands in for it exactly as it does for
 * Cloudflare. Presigning is local signing and needs no server at all.</p>
 */
public final class EmbeddedInfrastructure {

    /** Pinned versions - every backend test runs against exactly these. Verified by EmbeddedInfrastructureVersionIT. */
    public static final String POSTGRES_VERSION = "18.6";   // pom property embedded-postgres-binaries.version
    public static final String MONGODB_VERSION = "7.0.15";  // application-test.yaml de.flapdoodle.mongodb.embedded.version

    private static EmbeddedPostgres postgres;
    private static RedisServer redis;
    private static int redisPort;
    private static WireMockServer objectStorage;

    /** Not instantiable: static lifecycle holder. */
    private EmbeddedInfrastructure() {
    }

    /** Starts PostgreSQL and Redis once per JVM (later calls are no-ops) and registers a shutdown hook. */
    public static synchronized void start() {
        if (postgres != null) {
            return;
        }
        try {
            postgres = EmbeddedPostgres.builder().start();
            redisPort = freePort();
            redis = new RedisServer(redisPort);
            redis.start();
            objectStorage = new WireMockServer(wireMockConfig().dynamicPort());
            objectStorage.start();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Runtime.getRuntime().addShutdownHook(new Thread(EmbeddedInfrastructure::stop));
    }

    /** Stops Redis and PostgreSQL when the test JVM exits. */
    private static void stop() {
        try {
            if (objectStorage != null) objectStorage.stop();
            if (redis != null) redis.stop();
            if (postgres != null) postgres.close();
        } catch (IOException ignored) {
            // JVM is exiting
        }
    }

    /**
     * Port of the embedded PostgreSQL.
     *
     * @return TCP port
     */
    public static int postgresPort() {
        return postgres.getPort();
    }

    /**
     * Port of the embedded Redis.
     *
     * @return TCP port (random free port, never the local 6379)
     */
    public static int redisPort() {
        return redisPort;
    }

    /**
     * The WireMock server standing in for the S3 object store, so a test can stub and verify its calls.
     *
     * @return the running server
     */
    public static WireMockServer objectStorage() {
        start();
        return objectStorage;
    }

    /**
     * Base URL of the stubbed object store.
     *
     * @return the URL the application should use as its S3 endpoint
     */
    public static String objectStorageUrl() {
        return objectStorage().baseUrl();
    }

    /**
     * Asks the OS for a free TCP port.
     *
     * @return an unused port
     * @throws IOException if no socket can be opened
     */
    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            s.setReuseAddress(true);
            return s.getLocalPort();
        }
    }
}
