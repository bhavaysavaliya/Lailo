package com.lailo.application.common.cqrs;

import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;

/** In-process {@link QueryBus} backed by a type-to-handler map built once at startup. */
@Component
public class SimpleQueryBus implements QueryBus {

    private final HandlerRegistry<QueryHandler<?, ?>> registry;

    /**
     * Creates the bus from every {@link QueryHandler} bean.
     *
     * @param handlers all query handlers in the application context
     */
    public SimpleQueryBus(List<QueryHandler<?, ?>> handlers) {
        this.registry = new HandlerRegistry<>(handlers, QueryHandler::queryType);
    }

    /**
     * {@inheritDoc}
     * <p>Execution is deferred with {@code Mono.defer}, so nothing runs until the caller subscribes.</p>
     */
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
