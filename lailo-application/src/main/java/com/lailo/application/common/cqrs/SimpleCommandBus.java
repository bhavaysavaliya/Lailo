package com.lailo.application.common.cqrs;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * In-process {@link CommandBus} backed by a type-to-handler map.
 *
 * <p>Handlers are resolved lazily rather than injected as a constructor list. They have to be: a handler
 * that composes other use cases - checkout dispatching "clear cart", the saga dispatching "reserve stock" -
 * depends on the bus, and injecting every handler into the bus's constructor would make that a circular
 * dependency. The index is still built eagerly at the end of startup, so a duplicate handler is a startup
 * failure rather than a surprise at the first dispatch.</p>
 */
@Component
public class SimpleCommandBus implements CommandBus, SmartInitializingSingleton {

    private final ObjectProvider<CommandHandler<?, ?>> handlers;
    private volatile HandlerRegistry<CommandHandler<?, ?>> registry;

    /**
     * Creates the bus without touching any handler.
     *
     * @param handlers lazy provider for every command handler in the application context
     */
    public SimpleCommandBus(ObjectProvider<CommandHandler<?, ?>> handlers) {
        this.handlers = handlers;
    }

    /**
     * Builds the routing table once every singleton exists, which keeps the duplicate-handler check a
     * startup failure.
     */
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
    public <R, C extends Command<R>> Mono<R> dispatch(C command) {
        CommandHandler<C, R> handler = (CommandHandler<C, R>) index().find(command.getClass());
        if (handler == null) {
            return Mono.error(new IllegalStateException("No handler for " + command.getClass().getName()));
        }
        return Mono.defer(() -> handler.handle(command));
    }

    /**
     * Returns the routing table, building it on first use.
     *
     * @return the registry
     */
    private HandlerRegistry<CommandHandler<?, ?>> index() {
        HandlerRegistry<CommandHandler<?, ?>> current = registry;
        if (current == null) {
            synchronized (this) {
                current = registry;
                if (current == null) {
                    current = new HandlerRegistry<>(handlers.orderedStream().toList(),
                            CommandHandler::commandType);
                    registry = current;
                }
            }
        }
        return current;
    }
}
