package com.lailo.application.features.product.application.command;

import com.lailo.application.common.cqrs.Command;

import java.util.List;

/**
 * Puts reserved units back into stock: the compensating action for {@link ReserveStockCommand} when a
 * checkout fails after the reservation was taken.
 *
 * @param reference what is being released, normally the order id
 * @param lines the products and quantities to put back
 */
public record ReleaseStockCommand(String reference, List<StockLine> lines) implements Command<Boolean> {

    /** Rejects an empty release, which would report a compensation that never happened. */
    public ReleaseStockCommand {
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("lines must not be empty");
        }
        lines = List.copyOf(lines);
    }
}
