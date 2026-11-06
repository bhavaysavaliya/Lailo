package com.lailo.application.common.cqrs;

/**
 * Marker for a write-side intent that changes system state.
 *
 * @param <R> result type produced by the handler
 */
public interface Command<R> {
}
