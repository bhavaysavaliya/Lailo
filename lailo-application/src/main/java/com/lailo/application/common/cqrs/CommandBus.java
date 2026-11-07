package com.lailo.application.common.cqrs;

import reactor.core.publisher.Mono;

/** Entry point for the write side: routes a command to its single handler. */
public interface CommandBus {

    /**
     * Routes a command to the one handler registered for its exact class and executes it lazily.
     *
     * @param command the command to execute
     * @param <R> the result type the command declares
     * @param <C> the concrete command type
     * @return the handler's result; errors with {@code IllegalStateException} if no handler is registered
     */
    <R, C extends Command<R>> Mono<R> dispatch(C command);
}
