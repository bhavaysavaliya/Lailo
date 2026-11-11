package com.lailo.application.features.cart.api;

import com.lailo.application.common.cqrs.CommandBus;
import com.lailo.application.common.cqrs.QueryBus;
import com.lailo.application.features.cart.api.dto.AddToCartRequest;
import com.lailo.application.features.cart.api.dto.UpdateCartItemRequest;
import com.lailo.application.features.cart.application.command.AddToCartCommand;
import com.lailo.application.features.cart.application.command.ClearCartCommand;
import com.lailo.application.features.cart.application.command.RemoveFromCartCommand;
import com.lailo.application.features.cart.application.command.UpdateCartItemCommand;
import com.lailo.application.features.cart.application.query.CartView;
import com.lailo.application.features.cart.application.query.GetCartQuery;
import com.lailo.application.features.cart.domain.ClearCartReason;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * The signed-in shopper's cart. Every route acts on the caller's own cart - the user id comes from the
 * verified access token and is never accepted from the request, so one shopper can never reach another's.
 *
 * <p>Each write returns the whole cart, which is what a chat-rendered cart component needs anyway and saves
 * the client a follow-up read.</p>
 */
@RestController
@RequestMapping("/api/v1/cart")
@RequiredArgsConstructor
public class CartController {

    private final CommandBus commandBus;
    private final QueryBus queryBus;

    /**
     * Reads the caller's cart.
     *
     * @param jwt the verified access token; its subject is the user id
     * @return the cart, marked {@code no-store} because it is personal data
     */
    @GetMapping
    public Mono<ResponseEntity<CartView>> get(@AuthenticationPrincipal Jwt jwt) {
        return queryBus.ask(new GetCartQuery(userId(jwt)))
                .map(cart -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(cart));
    }

    /**
     * Adds units of a product to the cart.
     *
     * @param jwt the verified access token
     * @param req product and quantity
     * @return the updated cart
     */
    @PostMapping("/items")
    public Mono<CartView> add(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody AddToCartRequest req) {
        return commandBus.dispatch(new AddToCartCommand(userId(jwt), req.productId(), req.quantityOrOne()));
    }

    /**
     * Sets a line to an exact quantity.
     *
     * @param jwt the verified access token
     * @param productId product whose line to set
     * @param req the new quantity
     * @return the updated cart
     */
    @PutMapping("/items/{productId}")
    public Mono<CartView> update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID productId,
                                 @Valid @RequestBody UpdateCartItemRequest req) {
        return commandBus.dispatch(new UpdateCartItemCommand(userId(jwt), productId, req.quantity()));
    }

    /**
     * Removes a line.
     *
     * @param jwt the verified access token
     * @param productId product to remove
     * @return the updated cart, or 404 {@code CART_ITEM_NOT_FOUND}
     */
    @DeleteMapping("/items/{productId}")
    public Mono<CartView> remove(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID productId) {
        return commandBus.dispatch(new RemoveFromCartCommand(userId(jwt), productId));
    }

    /**
     * Empties the cart. Idempotent.
     *
     * @param jwt the verified access token
     * @return 204 No Content, also when the cart was already empty
     */
    @DeleteMapping
    public Mono<ResponseEntity<Void>> clear(@AuthenticationPrincipal Jwt jwt) {
        return commandBus.dispatch(new ClearCartCommand(userId(jwt), ClearCartReason.CLEARED_BY_USER))
                .thenReturn(ResponseEntity.noContent().build());
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
