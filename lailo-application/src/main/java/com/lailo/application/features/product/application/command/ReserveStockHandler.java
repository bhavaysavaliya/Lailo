package com.lailo.application.features.product.application.command;

import com.lailo.application.common.cqrs.CommandHandler;
import com.lailo.application.common.outbox.OutboxPublisher;
import com.lailo.application.features.product.application.ProductErrors;
import com.lailo.application.features.product.domain.event.ProductEvents;
import com.lailo.application.features.product.infrastructure.persistence.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;

/**
 * Reserves stock for a checkout. Every line is a conditional {@code UPDATE}, so overselling is prevented by
 * the database rather than by a read-then-write in the application, and all lines share one transaction: if
 * the last line is short, the earlier ones roll back.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReserveStockHandler implements CommandHandler<ReserveStockCommand, Boolean> {

    private final ProductRepository products;
    private final OutboxPublisher outbox;
    private final TransactionalOperator tx;
    private final Clock clock;

    /**
     * Reserves every line and appends one stock event per product.
     *
     * @param cmd the request
     * @return {@code true} when the whole order was reserved, or {@code PRODUCT_OUT_OF_STOCK} /
     *         {@code PRODUCT_NOT_FOUND} with nothing reserved
     */
    @Override
    public Mono<Boolean> handle(ReserveStockCommand cmd) {
        Instant now = Instant.now(clock);
        return Mono.defer(() -> Flux.fromIterable(cmd.lines())
                .concatMap(line -> reserve(line, now))
                .then(Mono.just(true))
                .as(tx::transactional)
                .doOnSuccess(ok -> log.debug("Reserved stock for {} ({} line(s))", cmd.reference(),
                        cmd.lines().size())));
    }

    /**
     * Reserves one line and records the resulting quantity.
     *
     * @param line product and units
     * @param now reservation time
     * @return completion signal, or an error when the units are not available
     */
    private Mono<Void> reserve(StockLine line, Instant now) {
        return products.reserveStock(line.productId(), line.quantity(), now)
                .flatMap(updated -> updated > 0
                        ? publishStock(line, now)
                        : explainFailure(line));
    }

    /**
     * Appends {@code ProductStockChanged} for the product just reserved from, inside the same transaction, so
     * the catalogue's stock figure follows the reservation.
     *
     * @param line product and units
     * @param now reservation time
     * @return completion signal
     */
    private Mono<Void> publishStock(StockLine line, Instant now) {
        return products.findById(line.productId())
                .flatMap(product -> outbox.append(ProductEvents.TOPIC, ProductEvents.AGGREGATE,
                        product.getId().toString(), ProductEvents.PRODUCT_STOCK_CHANGED,
                        ProductEvents.ProductStockChanged.of(product)))
                .then();
    }

    /**
     * Turns a rejected reservation into the right error: a missing or archived product is a 404 / 409, a
     * product that simply ran short reports how many units are left.
     *
     * @param line product and units that could not be reserved
     * @return always an error signal
     */
    private Mono<Void> explainFailure(StockLine line) {
        return products.findById(line.productId())
                .flatMap(product -> product.isArchived()
                        ? Mono.<Void>error(ProductErrors.archived())
                        : Mono.<Void>error(ProductErrors.outOfStock(line.productId(), line.quantity(),
                        product.getStockQuantity())))
                .switchIfEmpty(Mono.error(ProductErrors.notFound(line.productId())));
    }

    /**
     * Serves {@link ReserveStockCommand}.
     *
     * @return the command class
     */
    @Override
    public Class<ReserveStockCommand> commandType() {
        return ReserveStockCommand.class;
    }
}
