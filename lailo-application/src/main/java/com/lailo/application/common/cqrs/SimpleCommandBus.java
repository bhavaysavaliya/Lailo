package com.lailo.application.common.cqrs;

import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;

/** In-process {@link CommandBus} backed by a type-to-handler map built once at startup. */
@Component
public class SimpleCommandBus implements CommandBus {

    private final HandlerRegistry<CommandHandler<?, ?>> registry;

    /**
     * Creates the bus from every {@link CommandHandler} bean.
     *
     * @param handlers all command handlers in the application context
     */
    public SimpleCommandBus(List<CommandHandler<?, ?>> handlers) {
        this.registry = new HandlerRegistry<>(handlers, CommandHandler::commandType);
    }

    /**
     * {@inheritDoc}
     * <p>Execution is deferred with {@code Mono.defer}, so nothing runs until the caller subscribes.</p>
     */
    @Override
    @SuppressWarnings("unchecked")
    public <R, C extends Command<R>> Mono<R> dispatch(C command) {
        CommandHandler<C, R> handler = (CommandHandler<C, R>) registry.find(command.getClass());
        if (handler == null) {
            return Mono.error(new IllegalStateException("No handler for " + command.getClass().getName()));
        }
        return Mono.defer(() -> handler.handle(command));
    }
}
