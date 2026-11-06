package com.lailo.application.common.cqrs;

import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;

@Component
public class SimpleQueryBus implements QueryBus {

    private final HandlerRegistry<QueryHandler<?, ?>> registry;

    public SimpleQueryBus(List<QueryHandler<?, ?>> handlers) {
        this.registry = new HandlerRegistry<>(handlers, QueryHandler::queryType);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <R, Q extends Query<R>> Mono<R> ask(Q query) {
        QueryHandler<Q, R> handler = (QueryHandler<Q, R>) registry.find(query.getClass());
        if (handler == null) {
            return Mono.error(new IllegalStateException("No handler for " + query.getClass().getName()));
        }
        return Mono.defer(() -> handler.handle(query));
    }
}
