package com.lailo.application.common.saga;

import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Collection;
import java.util.UUID;

/** R2DBC repository for {@link SagaInstance} rows, with the compare-and-set transition the store needs. */
public interface SagaRepository extends ReactiveCrudRepository<SagaInstance, UUID> {

    /**
     * Finds one saga run.
     *
     * @param sagaType saga name
     * @param correlationId business key
     * @return the instance, or empty
     */
    Mono<SagaInstance> findBySagaTypeAndCorrelationId(String sagaType, String correlationId);

    /**
     * Moves the saga forward only if it still sits on one of the expected steps. Returning zero rows means
     * the move already happened (a redelivered event) or the saga has diverged - either way the caller must
     * do nothing.
     *
     * @param sagaType saga name
     * @param correlationId business key
     * @param fromSteps steps this transition is legal from
     * @param toStep step to move to
     * @param status status to record
     * @param now transition time
     * @return number of rows updated: 1 when the transition was applied, 0 when it was not
     */
    @Modifying
    @Query("""
            UPDATE saga_instances
            SET step = :toStep, status = :status, updated_at = :now, version = version + 1
            WHERE saga_type = :sagaType AND correlation_id = :correlationId AND step IN (:fromSteps)
            """)
    Mono<Integer> transition(String sagaType, String correlationId, Collection<String> fromSteps,
                             String toStep, SagaStatus status, Instant now);

    /**
     * Records why a saga stopped, without changing its step.
     *
     * @param sagaType saga name
     * @param correlationId business key
     * @param reason human-readable cause
     * @param now time the failure was recorded
     * @return number of rows updated
     */
    @Modifying
    @Query("""
            UPDATE saga_instances
            SET last_error = :reason, updated_at = :now, version = version + 1
            WHERE saga_type = :sagaType AND correlation_id = :correlationId
            """)
    Mono<Integer> recordError(String sagaType, String correlationId, String reason, Instant now);
}
