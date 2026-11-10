package com.lailo.application.features.product.application.command;

import com.lailo.application.common.cqrs.CommandHandler;
import com.lailo.application.common.outbox.OutboxPublisher;
import com.lailo.application.features.product.application.ProductErrors;
import com.lailo.application.features.product.domain.Product;
import com.lailo.application.features.product.domain.event.ProductEvents;
import com.lailo.application.features.product.infrastructure.persistence.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;

/** Soft-deletes a product. Idempotent: archiving an already archived product emits no second event. */
@Component
@RequiredArgsConstructor
public class ArchiveProductHandler implements CommandHandler<ArchiveProductCommand, Boolean> {

    private final ProductRepository products;
    private final OutboxPublisher outbox;
    private final TransactionalOperator tx;
    private final Clock clock;

    /**
     * Archives the product and appends {@code ProductArchived} in one transaction.
     *
     * @param cmd the request
     * @return {@code true} if this call archived the product, {@code false} if it already was, or
     *         {@code PRODUCT_NOT_FOUND}
     */
    @Override
    public Mono<Boolean> handle(ArchiveProductCommand cmd) {
        Instant now = Instant.now(clock);
        return Mono.defer(() -> products.findById(cmd.productId())
                .switchIfEmpty(Mono.error(ProductErrors.notFound(cmd.productId())))
                .<Boolean>flatMap(existing -> existing.isArchived()
                        ? Mono.just(false)
                        : archive(existing, now).as(tx::transactional)));
    }

    /**
     * Writes the archived state and its event.
     *
     * @param existing the product as stored
     * @param now archive time
     * @return always {@code true}
     */
    private Mono<Boolean> archive(Product existing, Instant now) {
        return products.save(existing.archive(now))
                .flatMap(saved -> outbox.append(ProductEvents.TOPIC, ProductEvents.AGGREGATE,
                        saved.getId().toString(), ProductEvents.PRODUCT_ARCHIVED,
                        ProductEvents.ProductArchived.of(saved)))
                .thenReturn(true);
    }

    /**
     * Serves {@link ArchiveProductCommand}.
     *
     * @return the command class
     */
    @Override
    public Class<ArchiveProductCommand> commandType() {
        return ArchiveProductCommand.class;
    }
}
