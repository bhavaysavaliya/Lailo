package com.lailo.application.common.cqrs;

import reactor.core.publisher.Mono;

/**
 * Handles exactly one {@link Query} type. Implementations are discovered by the {@link QueryBus}.
 */
public interface QueryHandler<Q extends Query<R>, R> {

    Mono<R> handle(Q query);

    Class<Q> queryType();
}
