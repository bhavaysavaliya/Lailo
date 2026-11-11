package com.lailo.application.features.order.api;

import com.lailo.application.common.cqrs.CommandBus;
import com.lailo.application.common.cqrs.QueryBus;
import com.lailo.application.features.auth.config.AuthBeansConfig;
import com.lailo.application.features.order.application.command.AdvanceOrderFulfilmentCommand;
import com.lailo.application.features.order.application.command.CancelOrderCommand;
import com.lailo.application.features.order.application.command.PlaceOrderCommand;
import com.lailo.application.features.order.application.query.GetOrderQuery;
import com.lailo.application.features.order.application.query.ListOrdersQuery;
import com.lailo.application.features.order.application.query.OrderCounts;
import com.lailo.application.features.order.application.query.OrderPage;
import com.lailo.application.features.order.application.query.OrderView;
import com.lailo.application.features.order.domain.CancelReason;
import com.lailo.application.features.order.domain.OrderBucket;
import com.lailo.application.features.order.domain.OrderStatus;
import com.lailo.application.features.payment.domain.PaymentMethod;
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
import java.time.Instant;
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

/** HTTP contract of the orders API: checkout, the two tabs, cancellation and the fulfilment transitions. */
@WebFluxTest(controllers = OrderController.class)
@Import({AuthConfig.class, AuthBeansConfig.class})
@TestPropertySource(properties = {
        "lailo.auth.otp.hash-secret=" + TestAuthProperties.OTP_SECRET,
        "lailo.auth.jwt.secret=" + TestAuthProperties.JWT_SECRET
})
class OrderControllerTest {

    private static final UUID USER_ID = UUID.fromString("aaaaaaaa-1111-4111-8111-aaaaaaaaaaaa");
    private static final UUID ORDER_ID = UUID.fromString("dddddddd-4444-4444-8444-dddddddddddd");

    @Autowired WebTestClient client;
    @MockitoBean CommandBus commandBus;
    @MockitoBean QueryBus queryBus;

    /**
     * Builds an order as the handlers would return it.
     *
     * @return an order waiting to be shipped
     */
    private static OrderView order() {
        return OrderView.builder()
                .id(ORDER_ID.toString())
                .orderNumber("LAILO-20261111-K7M2QX")
                .status(OrderStatus.CONFIRMED.name())
                .bucket(OrderBucket.TO_BE_SHIPPED.name())
                .items(List.of())
                .totalQuantity(2)
                .itemsTotal(new BigDecimal("1998.00"))
                .shippingFee(BigDecimal.ZERO)
                .totalAmount(new BigDecimal("1998.00"))
                .currency("INR")
                .paymentMethod("UPI")
                .shippingRecipient("Aarav Mehta")
                .shippingAddressLine("12 Chandni Chowk, New Delhi, Delhi 110006")
                .placedAt(Instant.parse("2026-11-11T10:00:00Z"))
                .build();
    }

    /**
     * A valid address body for checkout.
     *
     * @return the request body
     */
    private static Map<String, Object> checkoutBody() {
        return Map.of("paymentMethod", "UPI",
                "address", Map.of("fullName", "Aarav Mehta", "phone", "+919876543210",
                        "line1", "12 Chandni Chowk", "city", "New Delhi", "state", "Delhi",
                        "postalCode", "110006"));
    }

    /**
     * Checkout returns the created order and where to find it.
     * <ul>
     *   <li><b>Given</b> a signed-in caller and a valid address</li>
     *   <li><b>When</b> an order is placed</li>
     *   <li><b>Then</b> it returns 201 with a {@code Location} pointing at the order, and the command carries
     *       the normalised address and the caller's own user id</li>
     * </ul>
     */
    @Test
    void placesAnOrderAndReturnsWhereToFindIt() {
        when(commandBus.dispatch(any(PlaceOrderCommand.class))).thenReturn(Mono.just(order()));

        client.mutateWith(mockJwt().jwt(jwt -> jwt.subject(USER_ID.toString()))).mutateWith(csrf())
                .post().uri("/api/v1/orders")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(checkoutBody())
                .exchange()
                .expectStatus().isCreated()
                .expectHeader().location("/api/v1/orders/" + ORDER_ID)
                .expectBody().jsonPath("$.orderNumber").isEqualTo("LAILO-20261111-K7M2QX");

        ArgumentCaptor<PlaceOrderCommand> captor = ArgumentCaptor.forClass(PlaceOrderCommand.class);
        verify(commandBus).dispatch(captor.capture());
        assertThat(captor.getValue().userId()).isEqualTo(USER_ID);
        assertThat(captor.getValue().paymentMethod()).isEqualTo(PaymentMethod.UPI);
        assertThat(captor.getValue().address().city()).isEqualTo("New Delhi");
        assertThat(captor.getValue().address().country()).isEqualTo("IN");
    }

    /**
     * An undeliverable address is refused before any order exists.
     * <ul>
     *   <li><b>Given</b> an address with a malformed PIN code</li>
     *   <li><b>When</b> an order is placed</li>
     *   <li><b>Then</b> it is 400 {@code VALIDATION_FAILED} naming the field, and no command is
     *       dispatched</li>
     * </ul>
     */
    @Test
    void rejectsAnUndeliverableAddress() {
        Map<String, Object> body = Map.of("paymentMethod", "UPI",
                "address", Map.of("fullName", "Aarav Mehta", "phone", "+919876543210",
                        "line1", "12 Chandni Chowk", "city", "New Delhi", "state", "Delhi",
                        "postalCode", "11000"));

        client.mutateWith(mockJwt().jwt(jwt -> jwt.subject(USER_ID.toString()))).mutateWith(csrf())
                .post().uri("/api/v1/orders")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("VALIDATION_FAILED")
                .jsonPath("$.errors['address.postalCode']").exists();

        verifyNoInteractions(commandBus);
    }

    /**
     * The tab, page and size are bound from the query string, and default sensibly.
     * <ul>
     *   <li><b>Given</b> a signed-in caller</li>
     *   <li><b>When</b> the shipped tab is listed with explicit paging, and then the list is requested with
     *       no parameters at all</li>
     *   <li><b>Then</b> the first query carries {@code SHIPPED} with that paging and the second defaults to
     *       {@code ALL}, page 0, size 10</li>
     * </ul>
     */
    @Test
    void bindsTheTabAndPagingFromTheQueryString() {
        when(queryBus.ask(any(ListOrdersQuery.class))).thenReturn(Mono.just(OrderPage.builder()
                .items(List.of(order()))
                .bucket(OrderBucket.SHIPPED.name())
                .page(1).size(5).total(7).hasNext(true)
                .counts(new OrderCounts(2, 5, 0))
                .build()));

        client.mutateWith(mockJwt().jwt(jwt -> jwt.subject(USER_ID.toString())))
                .get().uri("/api/v1/orders?bucket=SHIPPED&page=1&size=5")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.counts.shipped").isEqualTo(5)
                .jsonPath("$.counts.toBeShipped").isEqualTo(2)
                .jsonPath("$.hasNext").isEqualTo(true);

        client.mutateWith(mockJwt().jwt(jwt -> jwt.subject(USER_ID.toString())))
                .get().uri("/api/v1/orders").exchange().expectStatus().isOk();

        ArgumentCaptor<ListOrdersQuery> captor = ArgumentCaptor.forClass(ListOrdersQuery.class);
        verify(queryBus, org.mockito.Mockito.times(2)).ask(captor.capture());
        assertThat(captor.getAllValues().getFirst().bucket()).isEqualTo(OrderBucket.SHIPPED);
        assertThat(captor.getAllValues().getFirst().page()).isEqualTo(1);
        assertThat(captor.getAllValues().getFirst().size()).isEqualTo(5);
        assertThat(captor.getAllValues().getLast().bucket()).isEqualTo(OrderBucket.ALL);
        assertThat(captor.getAllValues().getLast().page()).isZero();
        assertThat(captor.getAllValues().getLast().size()).isEqualTo(10);
    }

    /**
     * A tab nobody defined is a bad request rather than a silent "everything".
     * <ul>
     *   <li><b>Given</b> a signed-in caller</li>
     *   <li><b>When</b> the orders list is requested for a bucket that does not exist</li>
     *   <li><b>Then</b> it is 400 and no query runs</li>
     * </ul>
     */
    @Test
    void rejectsAnUnknownTab() {
        client.mutateWith(mockJwt().jwt(jwt -> jwt.subject(USER_ID.toString())))
                .get().uri("/api/v1/orders?bucket=IN_TRANSIT")
                .exchange().expectStatus().isBadRequest();

        verifyNoInteractions(queryBus);
    }

    /**
     * Cancelling returns no content and records who asked.
     * <ul>
     *   <li><b>Given</b> a signed-in caller</li>
     *   <li><b>When</b> they cancel their order</li>
     *   <li><b>Then</b> it is 204 and the command carries the caller's id and
     *       {@code REQUESTED_BY_CUSTOMER}, so support can tell a shopper's cancellation from a system
     *       one</li>
     * </ul>
     */
    @Test
    void cancelsAnOrderOnTheShoppersBehalf() {
        when(commandBus.dispatch(any(CancelOrderCommand.class))).thenReturn(Mono.just(true));

        client.mutateWith(mockJwt().jwt(jwt -> jwt.subject(USER_ID.toString()))).mutateWith(csrf())
                .post().uri("/api/v1/orders/" + ORDER_ID + "/cancel")
                .exchange().expectStatus().isNoContent();

        ArgumentCaptor<CancelOrderCommand> captor = ArgumentCaptor.forClass(CancelOrderCommand.class);
        verify(commandBus).dispatch(captor.capture());
        assertThat(captor.getValue().orderId()).isEqualTo(ORDER_ID);
        assertThat(captor.getValue().userId()).isEqualTo(USER_ID);
        assertThat(captor.getValue().reason()).isEqualTo(CancelReason.REQUESTED_BY_CUSTOMER);
    }

    /**
     * An order cannot be marked shipped without something to track it by.
     * <ul>
     *   <li><b>Given</b> an order the caller owns</li>
     *   <li><b>When</b> it is marked shipped with no courier or tracking number</li>
     *   <li><b>Then</b> it is 400 {@code TRACKING_DETAILS_REQUIRED} and nothing is dispatched, so an order
     *       never lands in the "shipped" tab with nothing for the shopper to follow</li>
     * </ul>
     */
    @Test
    void refusesToShipWithoutTrackingDetails() {
        when(queryBus.ask(any(GetOrderQuery.class))).thenReturn(Mono.just(order()));

        client.mutateWith(mockJwt().jwt(jwt -> jwt.subject(USER_ID.toString()))).mutateWith(csrf())
                .post().uri("/api/v1/orders/" + ORDER_ID + "/fulfilment")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("target", "SHIPPED"))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("TRACKING_DETAILS_REQUIRED");

        verifyNoInteractions(commandBus);
    }

    /**
     * Shipping with tracking details binds them all onto the command.
     * <ul>
     *   <li><b>Given</b> an order the caller owns</li>
     *   <li><b>When</b> it is marked shipped with a courier, a tracking number and a promised date</li>
     *   <li><b>Then</b> it is 204 and all three reach the command, which is what the shopper then sees in
     *       the shipped tab</li>
     * </ul>
     */
    @Test
    void bindsTheTrackingDetailsWhenShipping() {
        when(queryBus.ask(any(GetOrderQuery.class))).thenReturn(Mono.just(order()));
        when(commandBus.dispatch(any(AdvanceOrderFulfilmentCommand.class))).thenReturn(Mono.just(true));

        client.mutateWith(mockJwt().jwt(jwt -> jwt.subject(USER_ID.toString()))).mutateWith(csrf())
                .post().uri("/api/v1/orders/" + ORDER_ID + "/fulfilment")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("target", "SHIPPED", "courierName", "Delhivery",
                        "trackingNumber", "DL123456789IN", "expectedDeliveryDate", "2026-11-15"))
                .exchange().expectStatus().isNoContent();

        ArgumentCaptor<AdvanceOrderFulfilmentCommand> captor =
                ArgumentCaptor.forClass(AdvanceOrderFulfilmentCommand.class);
        verify(commandBus).dispatch(captor.capture());
        assertThat(captor.getValue().target()).isEqualTo(OrderStatus.SHIPPED);
        assertThat(captor.getValue().courierName()).isEqualTo("Delhivery");
        assertThat(captor.getValue().trackingNumber()).isEqualTo("DL123456789IN");
        assertThat(captor.getValue().expectedDeliveryDate())
                .isEqualTo(java.time.LocalDate.of(2026, 11, 15));
    }

    /**
     * A fulfilment step outside the pipeline is refused.
     * <ul>
     *   <li><b>Given</b> an order the caller owns</li>
     *   <li><b>When</b> it is asked to move back to {@code PENDING_PAYMENT}</li>
     *   <li><b>Then</b> the request fails rather than rewinding the order</li>
     * </ul>
     */
    @Test
    void refusesAFulfilmentStepOutsideThePipeline() {
        when(queryBus.ask(any(GetOrderQuery.class))).thenReturn(Mono.just(order()));

        client.mutateWith(mockJwt().jwt(jwt -> jwt.subject(USER_ID.toString()))).mutateWith(csrf())
                .post().uri("/api/v1/orders/" + ORDER_ID + "/fulfilment")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("target", "PENDING_PAYMENT"))
                .exchange().expectStatus().is5xxServerError();

        verifyNoInteractions(commandBus);
    }

    /**
     * Orders are closed to anonymous callers.
     * <ul>
     *   <li><b>Given</b> no access token</li>
     *   <li><b>When</b> the orders list, one order and checkout are requested</li>
     *   <li><b>Then</b> each is 401</li>
     * </ul>
     */
    @Test
    void requiresSignIn() {
        client.get().uri("/api/v1/orders").exchange().expectStatus().isUnauthorized();
        client.get().uri("/api/v1/orders/" + ORDER_ID).exchange().expectStatus().isUnauthorized();
        client.mutateWith(csrf()).post().uri("/api/v1/orders")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(checkoutBody())
                .exchange().expectStatus().isUnauthorized();
    }
}
