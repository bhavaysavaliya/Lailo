package com.lailo.application.features.wishlist.api;

import com.lailo.application.common.cqrs.CommandBus;
import com.lailo.application.common.cqrs.QueryBus;
import com.lailo.application.features.wishlist.api.dto.AddToWishlistRequest;
import com.lailo.application.features.wishlist.api.dto.MoveToCartRequest;
import com.lailo.application.features.wishlist.application.command.AddToWishlistCommand;
import com.lailo.application.features.wishlist.application.command.MoveWishlistItemToCartCommand;
import com.lailo.application.features.wishlist.application.command.RemoveFromWishlistCommand;
import com.lailo.application.features.wishlist.application.query.GetWishlistQuery;
import com.lailo.application.features.wishlist.application.query.WishlistView;
import com.lailo.application.features.wishlist.domain.RemoveWishlistItemReason;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * The signed-in shopper's wishlist. As with the cart, the user id always comes from the access token, never
 * from the request.
 */
@RestController
@RequestMapping("/api/v1/wishlist")
@RequiredArgsConstructor
public class WishlistController {

    private final CommandBus commandBus;
    private final QueryBus queryBus;

    /**
     * Reads the caller's wishlist.
     *
     * @param jwt the verified access token; its subject is the user id
     * @return the wishlist, marked {@code no-store} because it is personal data
     */
    @GetMapping
    public Mono<ResponseEntity<WishlistView>> get(@AuthenticationPrincipal Jwt jwt) {
        return queryBus.ask(new GetWishlistQuery(userId(jwt)))
                .map(list -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(list));
    }

    /**
     * Saves a product for later. Saving one that is already saved just updates its note.
     *
     * @param jwt the verified access token
     * @param req product and optional note
     * @return the updated wishlist
     */
    @PostMapping("/items")
    public Mono<WishlistView> add(@AuthenticationPrincipal Jwt jwt,
                                  @Valid @RequestBody AddToWishlistRequest req) {
        return commandBus.dispatch(new AddToWishlistCommand(userId(jwt), req.productId(), req.note()));
    }

    /**
     * Unsaves a product.
     *
     * @param jwt the verified access token
     * @param productId product to unsave
     * @return the updated wishlist, or 404 {@code WISHLIST_ITEM_NOT_FOUND}
     */
    @DeleteMapping("/items/{productId}")
    public Mono<WishlistView> remove(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID productId) {
        return commandBus.dispatch(new RemoveFromWishlistCommand(userId(jwt), productId,
                RemoveWishlistItemReason.REMOVED_BY_USER));
    }

    /**
     * Moves a saved product into the cart.
     *
     * @param jwt the verified access token
     * @param productId product to move
     * @param req units to add, or {@code null} for one
     * @return both updated lists, or the error the cart raised
     */
    @PostMapping("/items/{productId}/move-to-cart")
    public Mono<MoveWishlistItemToCartCommand.Result> moveToCart(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID productId,
            @Valid @RequestBody(required = false) MoveToCartRequest req) {
        int quantity = req == null ? 1 : req.quantityOrOne();
        return commandBus.dispatch(new MoveWishlistItemToCartCommand(userId(jwt), productId, quantity));
    }

    /**
     * Extracts the caller's user id from the access token.
     *
     * @param jwt the verified access token
     * @return the user id
     */
    private static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
