package com.lailo.application.common.cqrs;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * In-process {@link QueryBus} backed by a type-to-handler map.
 *
 * <p>Handlers are resolved lazily for the same reason as in {@link SimpleCommandBus}: a handler that reads
 * through the bus - the cart asking the catalogue for products, checkout asking for the cart - depends on
 * the bus, which a constructor list would turn into a cycle. The index is still built at the end of startup,
 * so duplicate handlers fail fast.</p>
 */
@Component
public class SimpleQueryBus implements QueryBus, SmartInitializingSingleton {

    private final ObjectProvider<QueryHandler<?, ?>> handlers;
    private volatile HandlerRegistry<QueryHandler<?, ?>> registry;

    /**
     * Creates the bus without touching any handler.
     *
     * @param handlers lazy provider for every query handler in the application context
     */
    public SimpleQueryBus(ObjectProvider<QueryHandler<?, ?>> handlers) {
        this.handlers = handlers;
    }

    /** Builds the routing table once every singleton exists, keeping the duplicate check at startup. */
    @Override
    public void afterSingletonsInstantiated() {
        index();
    }

    /**
     * {@inheritDoc}
     * <p>Execution is deferred with {@code Mono.defer}, so nothing runs until the caller subscribes.</p>
     */
    @Override
    @SuppressWarnings("unchecked")
    public <R, Q extends Query<R>> Mono<R> ask(Q query) {
        QueryHandler<Q, R> handler = (QueryHandler<Q, R>) index().find(query.getClass());
        if (handler == null) {
            return Mono.error(new IllegalStateException("No handler for " + query.getClass().getName()));
        }
        return Mono.defer(() -> handler.handle(query));
    }

    /**
     * Returns the routing table, building it on first use.
     *
     * @return the registry
     */
    private HandlerRegistry<QueryHandler<?, ?>> index() {
        HandlerRegistry<QueryHandler<?, ?>> current = registry;
        if (current == null) {
            synchronized (this) {
                current = registry;
                if (current == null) {
                    current = new HandlerRegistry<>(handlers.orderedStream().toList(),
                            QueryHandler::queryType);
                    registry = current;
                }
            }
        }
        return current;
    }
}
