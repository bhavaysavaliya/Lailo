package com.lailo.application.common.cqrs;

import reactor.core.publisher.Mono;

/**
 * Handles exactly one {@link Query} type. Implementations are discovered by the {@link QueryBus}.
 */
public interface QueryHandler<Q extends Query<R>, R> {

    /**
     * Runs the query against the read side. Must not change state.
     *
     * @param query the query to run
     * @return the query result, or an error signal
     */
    Mono<R> handle(Q query);

    /**
     * Declares which query class this handler serves; used by the bus to build its routing table.
     *
     * @return the exact query class
     */
    Class<Q> queryType();
}
