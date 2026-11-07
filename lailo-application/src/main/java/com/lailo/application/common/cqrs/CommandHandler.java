package com.lailo.application.common.cqrs;

import reactor.core.publisher.Mono;

/**
 * Handles exactly one {@link Command} type. Implementations are discovered by the {@link CommandBus}.
 */
public interface CommandHandler<C extends Command<R>, R> {

    /**
     * Executes the command. Implementations must not block; all I/O is reactive.
     *
     * @param command the command to execute
     * @return the result, or an error signal (typically an {@code ApiException}) for business-rule failures
     */
    Mono<R> handle(C command);

    /**
     * Declares which command class this handler serves; used by the bus to build its routing table.
     *
     * @return the exact command class (subclasses are not matched)
     */
    Class<C> commandType();
}
