package com.lailo.application.features.wishlist.application.command;

import com.lailo.application.common.cqrs.CommandBus;
import com.lailo.application.common.cqrs.CommandHandler;
import com.lailo.application.features.cart.application.command.AddToCartCommand;
import com.lailo.application.features.cart.application.query.CartView;
import com.lailo.application.features.wishlist.application.query.WishlistView;
import com.lailo.application.features.wishlist.domain.RemoveWishlistItemReason;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/**
 * Moves a saved product into the cart.
 *
 * <p>It composes the two existing commands through the bus rather than touching either table itself, and
 * runs them in one transaction: a shopper must never see the item leave the wishlist without arriving in the
 * cart. Because the add is attempted first, an out-of-stock product leaves the wishlist untouched and the
 * shopper keeps their saved item.</p>
 */
@Component
@RequiredArgsConstructor
public class MoveWishlistItemToCartHandler
        implements CommandHandler<MoveWishlistItemToCartCommand, MoveWishlistItemToCartCommand.Result> {

    private final CommandBus commandBus;
    private final TransactionalOperator tx;

    /**
     * Adds to the cart, then unsaves, then returns both lists.
     *
     * @param cmd the request
     * @return the updated cart and wishlist, or the error the add produced
     *         ({@code PRODUCT_OUT_OF_STOCK}, {@code PRODUCT_UNAVAILABLE}, {@code CART_QUANTITY_LIMIT})
     */
    @Override
    public Mono<MoveWishlistItemToCartCommand.Result> handle(MoveWishlistItemToCartCommand cmd) {
        return Mono.defer(() -> {
            Mono<CartView> addToCart = commandBus.dispatch(
                    new AddToCartCommand(cmd.userId(), cmd.productId(), Math.max(1, cmd.quantity())));
            Mono<WishlistView> unsave = commandBus.dispatch(new RemoveFromWishlistCommand(
                    cmd.userId(), cmd.productId(), RemoveWishlistItemReason.MOVED_TO_CART));
            return addToCart
                    .flatMap(cart -> unsave.map(wishlist -> MoveWishlistItemToCartCommand.Result.builder()
                            .cart(cart)
                            .wishlist(wishlist)
                            .build()))
                    .as(tx::transactional);
        });
    }

    /**
     * Serves {@link MoveWishlistItemToCartCommand}.
     *
     * @return the command class
     */
    @Override
    public Class<MoveWishlistItemToCartCommand> commandType() {
        return MoveWishlistItemToCartCommand.class;
    }
}
