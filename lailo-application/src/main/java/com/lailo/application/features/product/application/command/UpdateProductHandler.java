package com.lailo.application.features.product.application.command;

import com.lailo.application.common.cqrs.CommandHandler;
import com.lailo.application.common.outbox.OutboxPublisher;
import com.lailo.application.features.product.application.ProductErrors;
import com.lailo.application.features.product.application.query.ProductView;
import com.lailo.application.features.product.application.query.ProductViewMapper;
import com.lailo.application.features.product.domain.Product;
import com.lailo.application.features.product.domain.event.ProductEvents;
import com.lailo.application.features.product.domain.MediaStatus;
import com.lailo.application.features.product.infrastructure.persistence.ProductMediaRepository;
import com.lailo.application.features.product.infrastructure.persistence.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;

/**
 * Updates a product and appends {@code ProductUpdated} in one transaction. The event carries the full state,
 * so the catalogue needs no previous event to rebuild the entry.
 */
@Component
@RequiredArgsConstructor
public class UpdateProductHandler implements CommandHandler<UpdateProductCommand, ProductView> {

    private final ProductRepository products;
    private final ProductMediaRepository media;
    private final ProductViewMapper mapper;
    private final OutboxPublisher outbox;
    private final TransactionalOperator tx;
    private final Clock clock;

    /**
     * Loads the product, applies the new details and records the event.
     *
     * @param cmd the request
     * @return the updated product, or {@code PRODUCT_NOT_FOUND} / {@code PRODUCT_ARCHIVED}
     */
    @Override
    public Mono<ProductView> handle(UpdateProductCommand cmd) {
        Instant now = Instant.now(clock);
        return Mono.defer(() -> products.findById(cmd.productId())
                        .switchIfEmpty(Mono.error(ProductErrors.notFound(cmd.productId())))
                        .<Product>flatMap(existing -> existing.isArchived()
                                ? Mono.error(ProductErrors.archived())
                                : Mono.just(existing.applyDetails(cmd.details(), now)))
                        .flatMap(products::save)
                        .<Product>flatMap(saved -> outbox.append(ProductEvents.TOPIC, ProductEvents.AGGREGATE,
                                        saved.getId().toString(), ProductEvents.PRODUCT_UPDATED,
                                        ProductEvents.ProductUpdated.of(saved))
                                .thenReturn(saved))
                        .as(tx::transactional))
                .flatMap(saved -> media
                        .findByProductIdAndStatusOrderByPosition(saved.getId(), MediaStatus.READY)
                        .collectList()
                        .map(items -> mapper.fromAggregate(saved, items)));
    }

    /**
     * Serves {@link UpdateProductCommand}.
     *
     * @return the command class
     */
    @Override
    public Class<UpdateProductCommand> commandType() {
        return UpdateProductCommand.class;
    }
}
