package com.lailo.application.features.product.application.command;

import com.lailo.application.common.cqrs.CommandHandler;
import com.lailo.application.common.outbox.OutboxPublisher;
import com.lailo.application.features.product.application.query.ProductView;
import com.lailo.application.features.product.application.query.ProductViewMapper;
import com.lailo.application.features.product.domain.Product;
import com.lailo.application.features.product.domain.event.ProductEvents;
import com.lailo.application.features.product.infrastructure.persistence.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Inserts the product and appends {@code ProductCreated} in one transaction, so the catalogue can never be
 * told about a product that was rolled back.
 */
@Component
@RequiredArgsConstructor
public class CreateProductHandler implements CommandHandler<CreateProductCommand, ProductView> {

    private final ProductRepository products;
    private final ProductViewMapper mapper;
    private final OutboxPublisher outbox;
    private final TransactionalOperator tx;
    private final Clock clock;

    /**
     * Creates the product and returns the state just written - the write model, not the projection, so the
     * caller always reads its own write.
     *
     * @param cmd the request
     * @return the created product
     */
    @Override
    public Mono<ProductView> handle(CreateProductCommand cmd) {
        Instant now = Instant.now(clock);
        return Mono.defer(() -> {
            Product product = Product.create(UUID.randomUUID(), cmd.details(), now);
            return products.save(product)
                    .<Product>flatMap(saved -> outbox.append(ProductEvents.TOPIC, ProductEvents.AGGREGATE,
                                    saved.getId().toString(), ProductEvents.PRODUCT_CREATED,
                                    ProductEvents.ProductCreated.of(saved))
                            .thenReturn(saved))
                    .as(tx::transactional);
        }).map(saved -> mapper.fromAggregate(saved, List.of())); // a brand-new product has no media yet
    }

    /**
     * Serves {@link CreateProductCommand}.
     *
     * @return the command class
     */
    @Override
    public Class<CreateProductCommand> commandType() {
        return CreateProductCommand.class;
    }
}
