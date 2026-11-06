package com.lailo.application.common.cqrs;

import reactor.core.publisher.Mono;

/**
 * Handles exactly one {@link Command} type. Implementations are discovered by the {@link CommandBus}.
 */
public interface CommandHandler<C extends Command<R>, R> {

    Mono<R> handle(C command);

    Class<C> commandType();
}
