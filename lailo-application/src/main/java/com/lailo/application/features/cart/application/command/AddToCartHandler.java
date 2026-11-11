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
import com.lailo.application.features.product.domain.ProductStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Adds to the cart, checking against the catalogue first.
 *
 * <p>The product is read through the query bus, not the catalogue's repositories: the cart has no business
 * knowing whether a product is served from MongoDB or PostgreSQL, and routing through the bus means it
 * inherits the catalogue's read-your-write fallback for free.</p>
 */
@Component
@RequiredArgsConstructor
public class AddToCartHandler implements CommandHandler<AddToCartCommand, CartView> {

    private final CartItemRepository cartItems;
    private final CartReader reader;
    private final QueryBus queryBus;
    private final OutboxPublisher outbox;
    private final TransactionalOperator tx;
    private final Clock clock;

    /**
     * Validates the product and stock, then inserts or raises the line.
     *
     * @param cmd the request
     * @return the whole updated cart, or {@code PRODUCT_NOT_FOUND} / {@code PRODUCT_UNAVAILABLE} /
     *         {@code PRODUCT_OUT_OF_STOCK} / {@code CART_QUANTITY_LIMIT}
     */
    @Override
    public Mono<CartView> handle(AddToCartCommand cmd) {
        Instant now = Instant.now(clock);
        return Mono.defer(() -> {
            if (cmd.quantity() < 1 || cmd.quantity() > CartItem.MAX_QUANTITY) {
                return Mono.<CartView>error(CartErrors.quantityLimit());
            }
            return product(cmd.productId())
                    .flatMap(product -> apply(cmd, product, now))
                    .then(reader.read(cmd.userId()));
        });
    }

    /**
     * Reads the product the shopper is adding and refuses one that has left the catalogue.
     *
     * @param productId the product
     * @return the catalogue entry
     */
    private Mono<ProductView> product(UUID productId) {
        return queryBus.ask(new GetProductsQuery(Set.of(productId)))
                .flatMap(found -> found.isEmpty()
                        ? Mono.<ProductView>error(ProductErrors.notFound(productId))
                        : Mono.just(found.getFirst()))
                .flatMap(product -> ProductStatus.ACTIVE.name().equals(product.status())
                        ? Mono.just(product)
                        : Mono.error(CartErrors.productUnavailable(productId)));
    }

    /**
     * Writes the line and its event in one transaction.
     *
     * @param cmd the request
     * @param product the catalogue entry, for the live price and stock
     * @param now change time
     * @return completion signal
     */
    private Mono<Void> apply(AddToCartCommand cmd, ProductView product, Instant now) {
        return cartItems.findByUserIdAndProductId(cmd.userId(), cmd.productId())
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(existing -> {
                    int target = existing.map(CartItem::getQuantity).orElse(0) + cmd.quantity();
                    if (target > CartItem.MAX_QUANTITY) {
                        return Mono.<Void>error(CartErrors.quantityLimit());
                    }
                    if (product.stockQuantity() < target) {
                        return Mono.<Void>error(ProductErrors.outOfStock(cmd.productId(), target,
                                product.stockQuantity()));
                    }
                    return existing.isPresent()
                            ? raise(existing.get(), target, product, now)
                            : insert(cmd, target, product, now);
                })
                .as(tx::transactional);
    }

    /**
     * Inserts a brand-new line.
     *
     * @param cmd the request
     * @param quantity units for the new line
     * @param product the catalogue entry
     * @param now change time
     * @return completion signal
     */
    private Mono<Void> insert(AddToCartCommand cmd, int quantity, ProductView product, Instant now) {
        CartItem line = CartItem.create(cmd.userId(), cmd.productId(), quantity, product.price(),
                product.currency(), now);
        return cartItems.save(line)
                .flatMap(saved -> outbox.append(CartEvents.TOPIC, CartEvents.AGGREGATE,
                        saved.getUserId().toString(), CartEvents.CART_ITEM_ADDED,
                        new CartEvents.CartItemAdded(UUID.randomUUID().toString(), saved.getUserId().toString(),
                                saved.getProductId().toString(), saved.getQuantity(), now)))
                .then();
    }

    /**
     * Raises an existing line to the new total.
     *
     * @param existing the stored line
     * @param quantity units the line should hold
     * @param product the catalogue entry
     * @param now change time
     * @return completion signal
     */
    private Mono<Void> raise(CartItem existing, int quantity, ProductView product, Instant now) {
        return cartItems.save(existing.withQuantity(quantity, product.price(), now))
                .flatMap(saved -> outbox.append(CartEvents.TOPIC, CartEvents.AGGREGATE,
                        saved.getUserId().toString(), CartEvents.CART_ITEM_QUANTITY_CHANGED,
                        new CartEvents.CartItemQuantityChanged(UUID.randomUUID().toString(),
                                saved.getUserId().toString(), saved.getProductId().toString(),
                                saved.getQuantity(), now)))
                .then();
    }

    /**
     * Serves {@link AddToCartCommand}.
     *
     * @return the command class
     */
    @Override
    public Class<AddToCartCommand> commandType() {
        return AddToCartCommand.class;
    }
}
