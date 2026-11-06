package com.lailo.application.common.outbox;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Transactional outbox row. Written in the SAME database transaction as the state change,
 * then relayed to Kafka by {@link OutboxRelay}. Guarantees no event is lost and no event
 * is published for a rolled-back change (at-least-once delivery; consumers are idempotent).
 */
@Table("outbox_messages")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OutboxMessage {
    @Id
    private UUID id;
    private String aggregateType;
    private String aggregateId;
    private String eventType;
    private String topic;
    private String payload;
    private Instant createdAt;
    private Instant publishedAt;
    private int attempts;
    @Version
    private Long version;
}
