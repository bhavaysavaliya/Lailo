package com.lailo.application.support;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import redis.embedded.RedisServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;

/**
 * Real PostgreSQL and Redis processes started once per test JVM - no Docker required.
 * MongoDB comes from flapdoodle's auto-configuration ({@code de.flapdoodle.mongodb.embedded.version} in
 * application-test.yaml) and Kafka from {@code @EmbeddedKafka} on {@link IntegrationTest}.
 */
public final class EmbeddedInfrastructure {

    /** Pinned versions - every backend test runs against exactly these. Verified by EmbeddedInfrastructureVersionIT. */
    public static final String POSTGRES_VERSION = "18.6";   // pom property embedded-postgres-binaries.version
    public static final String MONGODB_VERSION = "7.0.15";  // application-test.yaml de.flapdoodle.mongodb.embedded.version

    private static EmbeddedPostgres postgres;
    private static RedisServer redis;
    private static int redisPort;

    private EmbeddedInfrastructure() {
    }

    public static synchronized void start() {
        if (postgres != null) {
            return;
        }
        try {
            postgres = EmbeddedPostgres.builder().start();
            redisPort = freePort();
            redis = new RedisServer(redisPort);
            redis.start();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Runtime.getRuntime().addShutdownHook(new Thread(EmbeddedInfrastructure::stop));
    }

    private static void stop() {
        try {
            if (redis != null) redis.stop();
            if (postgres != null) postgres.close();
        } catch (IOException ignored) {
            // JVM is exiting
        }
    }

    public static int postgresPort() {
        return postgres.getPort();
    }

    public static int redisPort() {
        return redisPort;
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            s.setReuseAddress(true);
            return s.getLocalPort();
        }
    }
}
