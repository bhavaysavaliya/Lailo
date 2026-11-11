package com.lailo.application.features.product.application.command;

import com.lailo.application.common.cqrs.CommandHandler;
import com.lailo.application.common.outbox.OutboxPublisher;
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
 * Puts reserved units back. Compensation must not fail on a product that has since been removed, so a
 * missing product is logged and skipped rather than aborting the release of the remaining lines - the goal
 * is to undo as much as can still be undone.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReleaseStockHandler implements CommandHandler<ReleaseStockCommand, Boolean> {

    private final ProductRepository products;
    private final OutboxPublisher outbox;
    private final TransactionalOperator tx;
    private final Clock clock;

    /**
     * Releases every line and appends one stock event per product that still exists.
     *
     * @param cmd the request
     * @return {@code true} once every line has been attempted
     */
    @Override
    public Mono<Boolean> handle(ReleaseStockCommand cmd) {
        Instant now = Instant.now(clock);
        return Mono.defer(() -> Flux.fromIterable(cmd.lines())
                .concatMap(line -> release(line, now))
                .then(Mono.just(true))
                .as(tx::transactional)
                .doOnSuccess(ok -> log.debug("Released stock for {} ({} line(s))", cmd.reference(),
                        cmd.lines().size())));
    }

    /**
     * Releases one line.
     *
     * @param line product and units
     * @param now release time
     * @return completion signal; empty for a product that no longer exists
     */
    private Mono<Void> release(StockLine line, Instant now) {
        return products.releaseStock(line.productId(), line.quantity(), now)
                .flatMap(updated -> {
                    if (updated == 0) {
                        log.warn("Could not release {} unit(s) of {}: product no longer exists",
                                line.quantity(), line.productId());
                        return Mono.empty();
                    }
                    return products.findById(line.productId())
                            .flatMap(product -> outbox.append(ProductEvents.TOPIC, ProductEvents.AGGREGATE,
                                    product.getId().toString(), ProductEvents.PRODUCT_STOCK_CHANGED,
                                    ProductEvents.ProductStockChanged.of(product)))
                            .then();
                });
    }

    /**
     * Serves {@link ReleaseStockCommand}.
     *
     * @return the command class
     */
    @Override
    public Class<ReleaseStockCommand> commandType() {
        return ReleaseStockCommand.class;
    }
}
