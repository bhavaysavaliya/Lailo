package com.lailo.application.common.cqrs;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/** Builds a type -> handler index and fails fast on duplicate registrations. */
final class HandlerRegistry<H> {

    private final Map<Class<?>, H> handlers = new HashMap<>();

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

    H find(Class<?> type) {
        return handlers.get(type);
    }
}
