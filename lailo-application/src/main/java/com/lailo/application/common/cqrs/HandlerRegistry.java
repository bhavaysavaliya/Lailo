package com.lailo.application.common.cqrs;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/** Builds a type -> handler index and fails fast on duplicate registrations. */
final class HandlerRegistry<H> {

    private final Map<Class<?>, H> handlers = new HashMap<>();

    /**
     * Indexes the handlers by the type each one serves.
     *
     * @param all every handler bean found in the context
     * @param keyFn extracts the served type from a handler
     * @throws IllegalStateException if two handlers declare the same type (fail fast at startup)
     */
    HandlerRegistry(Collection<? extends H> all, Function<H, Class<?>> keyFn) {
        for (H handler : all) {
            Class<?> key = keyFn.apply(handler);
            H previous = handlers.putIfAbsent(key, handler);
            if (previous != null) {
                throw new IllegalStateException("Duplicate handler for " + key.getName() + ": "
                        + previous.getClass().getName() + " and " + handler.getClass().getName());
            }
        }
    }

    /**
     * Looks up the handler for an exact type.
     *
     * @param type the command or query class
     * @return the handler, or {@code null} if none is registered
     */
    H find(Class<?> type) {
        return handlers.get(type);
    }
}
