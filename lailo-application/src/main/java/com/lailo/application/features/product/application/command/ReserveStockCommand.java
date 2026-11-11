package com.lailo.application.features.product.application.command;

import com.lailo.application.common.cqrs.Command;

import java.util.List;

/**
 * Takes the units an order needs out of stock. All lines succeed or none do, so a partially reserved order
 * can never exist.
 *
 * @param reference what the reservation is for, normally the order id; recorded in logs only
 * @param lines the products and quantities to reserve
 */
public record ReserveStockCommand(String reference, List<StockLine> lines) implements Command<Boolean> {

    /** A reservation with no lines would commit an empty transaction and report success; reject it instead. */
    public ReserveStockCommand {
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("lines must not be empty");
        }
        lines = List.copyOf(lines);
    }
}
