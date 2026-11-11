package com.lailo.application.features.order.api;

import com.lailo.application.common.cqrs.CommandBus;
import com.lailo.application.common.cqrs.QueryBus;
import com.lailo.application.common.web.ApiException;
import com.lailo.application.features.order.api.dto.AdvanceFulfilmentRequest;
import com.lailo.application.features.order.api.dto.PlaceOrderRequest;
import com.lailo.application.features.order.application.command.AdvanceOrderFulfilmentCommand;
import com.lailo.application.features.order.application.command.CancelOrderCommand;
import com.lailo.application.features.order.application.command.PlaceOrderCommand;
import com.lailo.application.features.order.application.query.GetOrderQuery;
import com.lailo.application.features.order.application.query.ListOrdersQuery;
import com.lailo.application.features.order.application.query.OrderPage;
import com.lailo.application.features.order.application.query.OrderView;
import com.lailo.application.features.order.domain.CancelReason;
import com.lailo.application.features.order.domain.OrderBucket;
import com.lailo.application.features.order.domain.OrderStatus;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.UUID;

/**
 * Orders: checkout, the shopper's own order list split into "to be shipped" and "shipped", and the
 * fulfilment transitions.
 *
 * <p>Every read and write is scoped to the caller's own orders by the user id in the access token.</p>
 *
 * <p>The fulfilment route is the exception, and the one place in this controller that will need to change:
 * packing and shipping are warehouse actions, so it belongs behind an operations role. No such role exists
 * yet - the JWT's {@code roles} claim is still hard-coded to {@code CUSTOMER} - so for now it is restricted
 * to the order's owner, which makes it usable and harmless in development without pretending to be
 * authorisation.</p>
 */
@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderController {

    private final CommandBus commandBus;
    private final QueryBus queryBus;

    /**
     * Turns the caller's cart into an order and starts the checkout saga.
     *
     * @param jwt the verified access token; its subject is the user id
     * @param req address and payment method
     * @return 201 with the placed order and its {@code Location}, or 409 {@code CART_EMPTY} /
     *         {@code CART_HAS_UNAVAILABLE_ITEMS} / {@code COD_UNAVAILABLE}
     */
    @PostMapping
    public Mono<ResponseEntity<OrderView>> place(@AuthenticationPrincipal Jwt jwt,
                                                 @Valid @RequestBody PlaceOrderRequest req) {
        return commandBus.dispatch(new PlaceOrderCommand(userId(jwt), req.address().toAddress(),
                        req.paymentMethod()))
                .map(order -> ResponseEntity.created(URI.create("/api/v1/orders/" + order.id()))
                        .cacheControl(CacheControl.noStore())
                        .body(order));
    }

    /**
     * Lists the caller's orders.
     *
     * @param jwt the verified access token
     * @param bucket which tab: {@code TO_BE_SHIPPED}, {@code SHIPPED}, {@code CANCELLED} or {@code ALL}
     * @param page zero-based page index
     * @param size page size, at most 50
     * @return the page, with counts for every tab
     */
    @GetMapping
    public Mono<ResponseEntity<OrderPage>> list(@AuthenticationPrincipal Jwt jwt,
                                                @RequestParam(defaultValue = "ALL") OrderBucket bucket,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "10") int size) {
        return queryBus.ask(ListOrdersQuery.builder()
                        .userId(userId(jwt))
                        .bucket(bucket)
                        .page(page)
                        .size(size)
                        .build())
                .map(result -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result));
    }

    /**
     * Reads one of the caller's orders.
     *
     * @param jwt the verified access token
     * @param orderId the order to read
     * @return the order, or 404 {@code ORDER_NOT_FOUND}
     */
    @GetMapping("/{orderId}")
    public Mono<ResponseEntity<OrderView>> get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID orderId) {
        return queryBus.ask(new GetOrderQuery(orderId, userId(jwt)))
                .map(order -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(order));
    }

    /**
     * Cancels one of the caller's orders. Releasing stock and refunding the payment follow asynchronously as
     * the saga's compensating steps.
     *
     * @param jwt the verified access token
     * @param orderId the order to cancel
     * @return 204 No Content, also when it was already cancelled, or 409 {@code ORDER_NOT_CANCELLABLE}
     */
    @PostMapping("/{orderId}/cancel")
    public Mono<ResponseEntity<Void>> cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID orderId) {
        return commandBus.dispatch(new CancelOrderCommand(orderId, userId(jwt),
                        CancelReason.REQUESTED_BY_CUSTOMER))
                .thenReturn(ResponseEntity.noContent().build());
    }

    /**
     * Moves an order along the fulfilment pipeline: packed, shipped or delivered. Shipping it is what moves
     * it from "to be shipped" into "shipped".
     *
     * @param jwt the verified access token
     * @param orderId the order to move
     * @param req the step, with tracking details when shipping
     * @return 204 No Content, or 409 {@code ORDER_STATUS_INVALID} / 400 when shipping without tracking
     *         details
     */
    @PostMapping("/{orderId}/fulfilment")
    public Mono<ResponseEntity<Void>> advance(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID orderId,
                                              @Valid @RequestBody AdvanceFulfilmentRequest req) {
        return queryBus.ask(new GetOrderQuery(orderId, userId(jwt)))
                .then(Mono.defer(() -> {
                    if (req.target() == OrderStatus.SHIPPED
                            && (isBlank(req.courierName()) || isBlank(req.trackingNumber()))) {
                        return Mono.<Boolean>error(new ApiException(HttpStatus.BAD_REQUEST,
                                "TRACKING_DETAILS_REQUIRED",
                                "Enter the courier and tracking number before marking an order shipped."));
                    }
                    return commandBus.dispatch(AdvanceOrderFulfilmentCommand.builder()
                            .orderId(orderId)
                            .target(req.target())
                            .courierName(req.courierName())
                            .trackingNumber(req.trackingNumber())
                            .expectedDeliveryDate(req.expectedDeliveryDate())
                            .build());
                }))
                .thenReturn(ResponseEntity.noContent().build());
    }

    /**
     * Whether a value was omitted or is only whitespace.
     *
     * @param value the value to check
     * @return {@code true} when there is nothing usable in it
     */
    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
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
