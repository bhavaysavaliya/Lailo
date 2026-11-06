package com.lailo.application.common.cqrs;

/**
 * Marker for a read-side request. Queries never mutate state.
 *
 * @param <R> result type produced by the handler
 */
public interface Query<R> {
}
