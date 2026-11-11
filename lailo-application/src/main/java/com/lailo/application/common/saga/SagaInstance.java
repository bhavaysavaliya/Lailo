package com.lailo.application.common.saga;

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
 * Durable state of one saga run. A saga coordinates a change that spans aggregates which cannot share a
 * transaction (an order, a payment and product stock), by reacting to events and issuing the next command.
 *
 * <p>The row exists for two reasons: it survives a restart mid-flow, and {@code step} is the
 * compare-and-set guard that makes a redelivered event a no-op instead of a second payment.</p>
 */
@Table("saga_instances")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SagaInstance {

    @Id
    private UUID id;
    /** Saga name, e.g. {@code Checkout}; one table holds every kind. */
    private String sagaType;
    /** Business key the saga follows, e.g. the order id. Unique per saga type. */
    private String correlationId;
    /** Current step; transitions are conditional on it, which is what makes replays safe. */
    private String step;
    private SagaStatus status;
    private String lastError;
    private Instant createdAt;
    private Instant updatedAt;
    /** Optimistic locking; null marks a new entity for Spring Data. */
    @Version
    private Long version;

    /**
     * Creates a new running instance at its first step.
     *
     * @param sagaType saga name
     * @param correlationId business key
     * @param step initial step
     * @param now start time
     * @return an unsaved instance with a {@code null} version, so Spring Data inserts it
     */
    public static SagaInstance start(String sagaType, String correlationId, String step, Instant now) {
        return SagaInstance.builder()
                .id(UUID.randomUUID())
                .sagaType(sagaType)
                .correlationId(correlationId)
                .step(step)
                .status(SagaStatus.RUNNING)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }
}
