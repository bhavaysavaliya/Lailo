package com.lailo.application.features.cart.api;

import com.lailo.application.common.cqrs.CommandBus;
import com.lailo.application.common.cqrs.QueryBus;
import com.lailo.application.features.auth.config.AuthBeansConfig;
import com.lailo.application.features.cart.application.command.AddToCartCommand;
import com.lailo.application.features.cart.application.command.ClearCartCommand;
import com.lailo.application.features.cart.application.command.RemoveFromCartCommand;
import com.lailo.application.features.cart.application.command.UpdateCartItemCommand;
import com.lailo.application.features.cart.application.query.CartView;
import com.lailo.application.features.cart.application.query.GetCartQuery;
import com.lailo.application.features.cart.domain.ClearCartReason;
import com.lailo.application.security.auth.config.AuthConfig;
import com.lailo.application.support.TestAuthProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.csrf;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

/** HTTP contract of the cart API: binding, validation, auth, CSRF and whose cart is touched. */
@WebFluxTest(controllers = CartController.class)
@Import({AuthConfig.class, AuthBeansConfig.class})
@TestPropertySource(properties = {
        "lailo.auth.otp.hash-secret=" + TestAuthProperties.OTP_SECRET,
        "lailo.auth.jwt.secret=" + TestAuthProperties.JWT_SECRET
})
class CartControllerTest {

    private static final UUID USER_ID = UUID.fromString("aaaaaaaa-1111-4111-8111-aaaaaaaaaaaa");
    private static final UUID OTHER_USER_ID = UUID.fromString("bbbbbbbb-2222-4222-8222-bbbbbbbbbbbb");
    private static final UUID PRODUCT_ID = UUID.fromString("cccccccc-3333-4333-8333-cccccccccccc");

    @Autowired WebTestClient client;
    @MockitoBean CommandBus commandBus;
    @MockitoBean QueryBus queryBus;

    /**
     * Builds a cart as the handlers would return it.
     *
     * @return a one-line cart
     */
    private static CartView cart() {
        return CartView.builder()
                .userId(USER_ID.toString())
                .items(List.of())
                .itemCount(1)
                .totalQuantity(2)
                .itemsTotal(new BigDecimal("1998.00"))
                .currency("INR")
                .hasUnavailableItems(false)
                .build();
    }

    /**
     * Reading the cart needs a token and is never cached.
     * <ul>
     *   <li><b>Given</b> a signed-in caller</li>
     *   <li><b>When</b> the cart is requested</li>
     *   <li><b>Then</b> it returns 200 with the cart and a {@code no-store} cache header, because a cart is
     *       personal data that must not sit in a shared cache</li>
     * </ul>
     */
    @Test
    void readsTheCallersCartWithoutCaching() {
        when(queryBus.ask(any(GetCartQuery.class))).thenReturn(Mono.just(cart()));

        client.mutateWith(mockJwt().jwt(jwt -> jwt.subject(USER_ID.toString())))
                .get().uri("/api/v1/cart")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().cacheControl(org.springframework.http.CacheControl.noStore())
                .expectBody().jsonPath("$.itemCount").isEqualTo(1);
    }

    /**
     * The cart that is touched is always the caller's own.
     * <ul>
     *   <li><b>Given</b> a caller whose token says one user id, and a body that names a different one</li>
     *   <li><b>When</b> a product is added</li>
     *   <li><b>Then</b> the command carries the id from the token, so a crafted body cannot reach another
     *       shopper's cart</li>
     * </ul>
     */
    @Test
    void takesTheUserIdFromTheTokenNotTheBody() {
        when(commandBus.dispatch(any(AddToCartCommand.class))).thenReturn(Mono.just(cart()));

        client.mutateWith(mockJwt().jwt(jwt -> jwt.subject(USER_ID.toString()))).mutateWith(csrf())
                .post().uri("/api/v1/cart/items")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("productId", PRODUCT_ID.toString(), "quantity", 2,
                        "userId", OTHER_USER_ID.toString()))
                .exchange()
                .expectStatus().isOk();

        ArgumentCaptor<AddToCartCommand> captor = ArgumentCaptor.forClass(AddToCartCommand.class);
        verify(commandBus).dispatch(captor.capture());
        assertThat(captor.getValue().userId()).isEqualTo(USER_ID);
        assertThat(captor.getValue().productId()).isEqualTo(PRODUCT_ID);
        assertThat(captor.getValue().quantity()).isEqualTo(2);
    }

    /**
     * A quantity outside the accepted range never reaches a handler.
     * <ul>
     *   <li><b>Given</b> a signed-in caller</li>
     *   <li><b>When</b> a product is added with a quantity of zero, and then with 21</li>
     *   <li><b>Then</b> both are 400 {@code VALIDATION_FAILED} and no command is dispatched</li>
     * </ul>
     */
    @Test
    void rejectsAnOutOfRangeQuantity() {
        client.mutateWith(mockJwt().jwt(jwt -> jwt.subject(USER_ID.toString()))).mutateWith(csrf())
                .post().uri("/api/v1/cart/items")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("productId", PRODUCT_ID.toString(), "quantity", 0))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("VALIDATION_FAILED");

        client.mutateWith(mockJwt().jwt(jwt -> jwt.subject(USER_ID.toString()))).mutateWith(csrf())
                .put().uri("/api/v1/cart/items/" + PRODUCT_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("quantity", 21))
                .exchange()
                .expectStatus().isBadRequest();

        verifyNoInteractions(commandBus);
    }

    /**
     * The quantity and product on the path are bound to the update command.
     * <ul>
     *   <li><b>Given</b> a signed-in caller</li>
     *   <li><b>When</b> a line is set to three units</li>
     *   <li><b>Then</b> the command carries the product from the path and the quantity from the body</li>
     * </ul>
     */
    @Test
    void bindsTheUpdateFromThePathAndBody() {
        when(commandBus.dispatch(any(UpdateCartItemCommand.class))).thenReturn(Mono.just(cart()));

        client.mutateWith(mockJwt().jwt(jwt -> jwt.subject(USER_ID.toString()))).mutateWith(csrf())
                .put().uri("/api/v1/cart/items/" + PRODUCT_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("quantity", 3))
                .exchange()
                .expectStatus().isOk();

        ArgumentCaptor<UpdateCartItemCommand> captor =
                ArgumentCaptor.forClass(UpdateCartItemCommand.class);
        verify(commandBus).dispatch(captor.capture());
        assertThat(captor.getValue().productId()).isEqualTo(PRODUCT_ID);
        assertThat(captor.getValue().quantity()).isEqualTo(3);
    }

    /**
     * Removing a line returns the updated cart and clearing it returns no content.
     * <ul>
     *   <li><b>Given</b> a signed-in caller</li>
     *   <li><b>When</b> a line is deleted and then the whole cart is deleted</li>
     *   <li><b>Then</b> the first returns 200 with the cart and the second 204, and the clear is recorded as
     *       the shopper's own action rather than a checkout</li>
     * </ul>
     */
    @Test
    void removesALineAndClearsTheCart() {
        when(commandBus.dispatch(any(RemoveFromCartCommand.class))).thenReturn(Mono.just(cart()));
        when(commandBus.dispatch(any(ClearCartCommand.class))).thenReturn(Mono.just(true));

        client.mutateWith(mockJwt().jwt(jwt -> jwt.subject(USER_ID.toString()))).mutateWith(csrf())
                .delete().uri("/api/v1/cart/items/" + PRODUCT_ID)
                .exchange().expectStatus().isOk();
        client.mutateWith(mockJwt().jwt(jwt -> jwt.subject(USER_ID.toString()))).mutateWith(csrf())
                .delete().uri("/api/v1/cart")
                .exchange().expectStatus().isNoContent();

        ArgumentCaptor<ClearCartCommand> captor = ArgumentCaptor.forClass(ClearCartCommand.class);
        verify(commandBus).dispatch(captor.capture());
        assertThat(captor.getValue().reason()).isEqualTo(ClearCartReason.CLEARED_BY_USER);
    }

    /**
     * The cart is closed to anonymous callers and to requests without a CSRF token.
     * <ul>
     *   <li><b>Given</b> no access token, and then a token but no CSRF token</li>
     *   <li><b>When</b> the cart is read and written</li>
     *   <li><b>Then</b> an anonymous read is 401, an anonymous write that does carry a CSRF token is 401,
     *       and a signed-in write with no CSRF token is 403 - the CSRF filter runs before authentication,
     *       so a request missing both is refused as a CSRF failure</li>
     * </ul>
     */
    @Test
    void requiresSignInAndCsrf() {
        client.get().uri("/api/v1/cart").exchange().expectStatus().isUnauthorized();

        client.mutateWith(csrf())
                .post().uri("/api/v1/cart/items")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("productId", PRODUCT_ID.toString(), "quantity", 1))
                .exchange().expectStatus().isUnauthorized();

        client.mutateWith(mockJwt().jwt(jwt -> jwt.subject(USER_ID.toString())))
                .post().uri("/api/v1/cart/items")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("productId", PRODUCT_ID.toString(), "quantity", 1))
                .exchange().expectStatus().isForbidden();

        verifyNoInteractions(commandBus);
    }
}
