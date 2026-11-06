package com.lailo.application.common.cqrs;

import reactor.core.publisher.Mono;

/** Entry point for the write side: routes a command to its single handler. */
public interface CommandBus {

    <R, C extends Command<R>> Mono<R> dispatch(C command);
}
