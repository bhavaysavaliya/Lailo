package com.lailo.application.features.wishlist.application.command;

import com.lailo.application.common.cqrs.Command;
import com.lailo.application.features.cart.application.query.CartView;
import com.lailo.application.features.wishlist.application.query.WishlistView;
import lombok.Builder;

import java.util.UUID;

/**
 * Moves a saved product into the cart in one step, which is the action shoppers actually take on a wishlist.
 *
 * @param userId whose lists to change
 * @param productId product to move
 * @param quantity units to add to the cart
 */
public record MoveWishlistItemToCartCommand(UUID userId, UUID productId, int quantity)
        implements Command<MoveWishlistItemToCartCommand.Result> {

    /**
     * Both lists after the move, so the client can re-render the cart and the wishlist from one response.
     *
     * @param cart the updated cart
     * @param wishlist the updated wishlist
     */
    @Builder
    public record Result(CartView cart, WishlistView wishlist) {
    }
}
