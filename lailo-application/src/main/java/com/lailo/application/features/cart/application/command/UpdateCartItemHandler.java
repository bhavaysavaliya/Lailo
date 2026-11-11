package com.lailo.application.features.cart.application.command;

import com.lailo.application.common.cqrs.CommandHandler;
import com.lailo.application.common.cqrs.QueryBus;
import com.lailo.application.common.outbox.OutboxPublisher;
import com.lailo.application.features.cart.application.CartErrors;
import com.lailo.application.features.cart.application.query.CartReader;
import com.lailo.application.features.cart.application.query.CartView;
import com.lailo.application.features.cart.domain.CartItem;
import com.lailo.application.features.cart.domain.event.CartEvents;
import com.lailo.application.features.cart.infrastructure.persistence.CartItemRepository;
import com.lailo.application.features.product.application.ProductErrors;
import com.lailo.application.features.product.application.query.GetProductsQuery;
import com.lailo.application.features.product.application.query.ProductView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Sets a cart line to an exact quantity, re-checking stock because it may have moved since the add. */
@Component
@RequiredArgsConstructor
public class UpdateCartItemHandler implements CommandHandler<UpdateCartItemCommand, CartView> {

    private final CartItemRepository cartItems;
    private final CartReader reader;
    private final QueryBus queryBus;
    private final OutboxPublisher outbox;
    private final TransactionalOperator tx;
    private final Clock clock;

    /**
     * Updates the line and appends its event in one transaction.
     *
     * @param cmd the request
     * @return the whole updated cart, or {@code CART_ITEM_NOT_FOUND} / {@code CART_QUANTITY_LIMIT} /
     *         {@code PRODUCT_OUT_OF_STOCK}
     */
    @Override
    public Mono<CartView> handle(UpdateCartItemCommand cmd) {
        Instant now = Instant.now(clock);
        return Mono.defer(() -> {
            if (cmd.quantity() < 1 || cmd.quantity() > CartItem.MAX_QUANTITY) {
                return Mono.<CartView>error(CartErrors.quantityLimit());
            }
            return cartItems.findByUserIdAndProductId(cmd.userId(), cmd.productId())
                    .switchIfEmpty(Mono.error(CartErrors.itemNotFound(cmd.productId())))
                    .flatMap(line -> liveProduct(cmd.productId())
                            .flatMap(product -> product.stockQuantity() < cmd.quantity()
                                    ? Mono.<Void>error(ProductErrors.outOfStock(cmd.productId(), cmd.quantity(),
                                    product.stockQuantity()))
                                    : save(line, cmd.quantity(), product, now)))
                    .as(tx::transactional)
                    .then(reader.read(cmd.userId()));
        });
    }

    /**
     * Reads the product for its current price and stock.
     *
     * @param productId the product on the line
     * @return the catalogue entry
     */
    private Mono<ProductView> liveProduct(UUID productId) {
        return queryBus.ask(new GetProductsQuery(Set.of(productId)))
                .flatMap(found -> found.isEmpty()
                        ? Mono.<ProductView>error(CartErrors.productUnavailable(productId))
                        : Mono.just(found.getFirst()));
    }

    /**
     * Stores the new quantity and appends the event.
     *
     * @param line the stored line
     * @param quantity units the line should hold
     * @param product the catalogue entry
     * @param now change time
     * @return completion signal
     */
    private Mono<Void> save(CartItem line, int quantity, ProductView product, Instant now) {
        return cartItems.save(line.withQuantity(quantity, product.price(), now))
                .flatMap(saved -> outbox.append(CartEvents.TOPIC, CartEvents.AGGREGATE,
                        saved.getUserId().toString(), CartEvents.CART_ITEM_QUANTITY_CHANGED,
                        new CartEvents.CartItemQuantityChanged(UUID.randomUUID().toString(),
                                saved.getUserId().toString(), saved.getProductId().toString(), quantity, now)))
                .then();
    }

    /**
     * Serves {@link UpdateCartItemCommand}.
     *
     * @return the command class
     */
    @Override
    public Class<UpdateCartItemCommand> commandType() {
        return UpdateCartItemCommand.class;
    }
}
