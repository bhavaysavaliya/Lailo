package com.lailo.application.common.cqrs;

import reactor.core.publisher.Mono;

/** Entry point for the read side: routes a query to its single handler. */
public interface QueryBus {

    <R, Q extends Query<R>> Mono<R> ask(Q query);
}
