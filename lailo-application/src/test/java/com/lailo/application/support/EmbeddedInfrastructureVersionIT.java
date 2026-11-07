package com.lailo.application.support;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.r2dbc.core.DatabaseClient;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Guards the pinned test infrastructure: fails if a dependency bump silently changes the embedded versions. */
class EmbeddedInfrastructureVersionIT extends IntegrationTest {

    @Autowired DatabaseClient db;
    @Autowired ReactiveMongoTemplate mongo;
    @Autowired ReactiveStringRedisTemplate redis;
    @Autowired KafkaTemplate<String, String> kafka;
    @Value("${de.flapdoodle.mongodb.embedded.version}") String configuredMongoVersion;

    /**
     * Tests run on the pinned PostgreSQL version.
     * <ul>
     *   <li><b>Given</b> the embedded PostgreSQL</li>
     *   <li><b>When</b> {@code SHOW server_version} is queried</li>
     *   <li><b>Then</b> it starts with {@code 18.6}</li>
     * </ul>
     */
    @Test
    void postgresIsPinnedVersion() {
        String version = db.sql("SHOW server_version").map(r -> r.get(0, String.class)).one().block();
        assertThat(version).startsWith(EmbeddedInfrastructure.POSTGRES_VERSION);
    }

    /**
     * Tests run on the pinned MongoDB version.
     * <ul>
     *   <li><b>Given</b> the flapdoodle-managed MongoDB</li>
     *   <li><b>When</b> the configured version and {@code buildInfo} are read</li>
     *   <li><b>Then</b> both are exactly {@code 7.0.15}</li>
     * </ul>
     */
    @Test
    void mongoIsPinnedVersion() {
        Document info = mongo.executeCommand(new Document("buildInfo", 1)).block();
        assertThat(info).isNotNull();
        assertThat(configuredMongoVersion).isEqualTo(EmbeddedInfrastructure.MONGODB_VERSION);
        assertThat(info.getString("version")).isEqualTo(EmbeddedInfrastructure.MONGODB_VERSION);
    }

    /**
     * Tests use the embedded Redis, not a local one.
     * <ul>
     *   <li><b>Given</b> the application's Redis template</li>
     *   <li><b>When</b> a value is written and read back</li>
     *   <li><b>Then</b> it round-trips and the port is not the standard 6379</li>
     * </ul>
     */
    @Test
    void redisIsEmbeddedAndReachable() {
        assertThat(redis.opsForValue().set("infra:ping", "pong").then(redis.opsForValue().get("infra:ping")).block())
                .isEqualTo("pong");
        assertThat(EmbeddedInfrastructure.redisPort()).isNotEqualTo(6379); // never the local brew Redis
    }

    /**
     * Tests use the embedded Kafka broker.
     * <ul>
     *   <li><b>Given</b> the application's Kafka template</li>
     *   <li><b>When</b> a record is sent to the user-events topic</li>
     *   <li><b>Then</b> the broker acknowledges it with an offset</li>
     * </ul>
     *
     * @throws Exception if the send does not complete within 10 s
     */
    @Test
    void kafkaIsEmbeddedAndReachable() throws Exception {
        assertThat(kafka.send(com.lailo.application.features.auth.domain.event.AuthEvents.TOPIC, "infra-check", "{}").get(10, TimeUnit.SECONDS).getRecordMetadata().offset())
                .isGreaterThanOrEqualTo(0);
    }
}
