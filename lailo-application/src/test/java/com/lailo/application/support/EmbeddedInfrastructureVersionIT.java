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

    @Test
    void postgresIsPinnedVersion() {
        String version = db.sql("SHOW server_version").map(r -> r.get(0, String.class)).one().block();
        assertThat(version).startsWith(EmbeddedInfrastructure.POSTGRES_VERSION);
    }

    @Test
    void mongoIsPinnedVersion() {
        Document info = mongo.executeCommand(new Document("buildInfo", 1)).block();
        assertThat(info).isNotNull();
        assertThat(configuredMongoVersion).isEqualTo(EmbeddedInfrastructure.MONGODB_VERSION);
        assertThat(info.getString("version")).isEqualTo(EmbeddedInfrastructure.MONGODB_VERSION);
    }

    @Test
    void redisIsEmbeddedAndReachable() {
        assertThat(redis.opsForValue().set("infra:ping", "pong").then(redis.opsForValue().get("infra:ping")).block())
                .isEqualTo("pong");
        assertThat(EmbeddedInfrastructure.redisPort()).isNotEqualTo(6379); // never the local brew Redis
    }

    @Test
    void kafkaIsEmbeddedAndReachable() throws Exception {
        assertThat(kafka.send(com.lailo.application.features.auth.domain.event.AuthEvents.TOPIC, "infra-check", "{}").get(10, TimeUnit.SECONDS).getRecordMetadata().offset())
                .isGreaterThanOrEqualTo(0);
    }
}
