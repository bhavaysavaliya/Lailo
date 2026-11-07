package com.lailo.application.common.cqrs;

import reactor.core.publisher.Mono;

/** Entry point for the read side: routes a query to its single handler. */
public interface QueryBus {

    /**
     * Routes a query to the one handler registered for its exact class and executes it lazily.
     *
     * @param query the query to run
     * @param <R> the result type the query declares
     * @param <Q> the concrete query type
     * @return the handler's result; errors with {@code IllegalStateException} if no handler is registered
     */
    <R, Q extends Query<R>> Mono<R> ask(Q query);
}
