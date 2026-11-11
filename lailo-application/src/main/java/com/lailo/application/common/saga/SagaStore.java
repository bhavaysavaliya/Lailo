package com.lailo.application.common.saga;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Shared state machine for saga orchestrators. Every method answers one question - "am I the one who should
 * act now?" - so an orchestrator never has to reason about duplicate deliveries itself.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SagaStore {

    private final SagaRepository sagas;
    private final Clock clock;

    /**
     * Starts a run, or reports that one already exists.
     *
     * @param sagaType saga name
     * @param correlationId business key, e.g. the order id
     * @param initialStep first step
     * @return {@code true} when this call created the run, {@code false} when it was already started
     */
    public Mono<Boolean> start(String sagaType, String correlationId, String initialStep) {
        return sagas.save(SagaInstance.start(sagaType, correlationId, initialStep, Instant.now(clock)))
                .thenReturn(true)
                .onErrorResume(DuplicateKeyException.class, ex -> {
                    log.debug("Saga {} for {} already started", sagaType, correlationId);
                    return Mono.just(false);
                });
    }

    /**
     * Attempts one step transition.
     *
     * @param sagaType saga name
     * @param correlationId business key
     * @param fromStep step the saga must currently be on
     * @param toStep step to move to
     * @param status status to record
     * @return {@code true} when the transition was applied and the caller should act
     */
    public Mono<Boolean> transition(String sagaType, String correlationId, String fromStep, String toStep,
                                    SagaStatus status) {
        return transition(sagaType, correlationId, Set.of(fromStep), toStep, status);
    }

    /**
     * Attempts one step transition that is legal from several steps, which compensation typically is.
     *
     * @param sagaType saga name
     * @param correlationId business key
     * @param fromSteps steps the transition is legal from
     * @param toStep step to move to
     * @param status status to record
     * @return {@code true} when the transition was applied and the caller should act
     */
    public Mono<Boolean> transition(String sagaType, String correlationId, Set<String> fromSteps, String toStep,
                                    SagaStatus status) {
        return sagas.transition(sagaType, correlationId, List.copyOf(fromSteps), toStep, status, Instant.now(clock))
                .map(updated -> updated > 0)
                .doOnNext(applied -> {
                    if (!applied) {
                        log.debug("Saga {} for {} is not on {} - skipping move to {}",
                                sagaType, correlationId, fromSteps, toStep);
                    }
                });
    }

    /**
     * Records why a saga stopped, for support and alerting.
     *
     * @param sagaType saga name
     * @param correlationId business key
     * @param reason human-readable cause
     * @return completion signal
     */
    public Mono<Void> recordError(String sagaType, String correlationId, String reason) {
        return sagas.recordError(sagaType, correlationId, reason, Instant.now(clock)).then();
    }

    /**
     * Reads a saga run, mostly for tests and support tooling.
     *
     * @param sagaType saga name
     * @param correlationId business key
     * @return the instance, or empty
     */
    public Mono<SagaInstance> find(String sagaType, String correlationId) {
        return sagas.findBySagaTypeAndCorrelationId(sagaType, correlationId);
    }
}
