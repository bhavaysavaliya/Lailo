package com.lailo.application.common.cqrs;

import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;

@Component
public class SimpleCommandBus implements CommandBus {

    private final HandlerRegistry<CommandHandler<?, ?>> registry;

    public SimpleCommandBus(List<CommandHandler<?, ?>> handlers) {
        this.registry = new HandlerRegistry<>(handlers, CommandHandler::commandType);
    }

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
