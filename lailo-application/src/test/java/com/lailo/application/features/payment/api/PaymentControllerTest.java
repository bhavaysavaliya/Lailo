package com.lailo.application.features.payment.api;

import com.lailo.application.common.cqrs.CommandBus;
import com.lailo.application.common.cqrs.QueryBus;
import com.lailo.application.features.auth.config.AuthBeansConfig;
import com.lailo.application.features.payment.application.command.ConfirmPaymentCommand;
import com.lailo.application.features.payment.application.command.HandlePaymentWebhookCommand;
import com.lailo.application.features.payment.application.query.GetPaymentQuery;
import com.lailo.application.features.payment.application.query.PaymentView;
import com.lailo.application.features.payment.domain.PaymentStatus;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.csrf;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

/**
 * HTTP contract of the payment API, including the one route in the application that is public and
 * CSRF-exempt: the provider's callback.
 */
@WebFluxTest(controllers = PaymentController.class)
@Import({AuthConfig.class, AuthBeansConfig.class})
@TestPropertySource(properties = {
        "lailo.auth.otp.hash-secret=" + TestAuthProperties.OTP_SECRET,
        "lailo.auth.jwt.secret=" + TestAuthProperties.JWT_SECRET
})
class PaymentControllerTest {

    private static final UUID USER_ID = UUID.fromString("aaaaaaaa-1111-4111-8111-aaaaaaaaaaaa");
    private static final UUID PAYMENT_ID = UUID.fromString("eeeeeeee-5555-4555-8555-eeeeeeeeeeee");
    private static final UUID ORDER_ID = UUID.fromString("dddddddd-4444-4444-8444-dddddddddddd");

    @Autowired WebTestClient client;
    @MockitoBean CommandBus commandBus;
    @MockitoBean QueryBus queryBus;

    /**
     * Builds a payment as the handlers would return it.
     *
     * @return an authorised payment
     */
    private static PaymentView payment() {
        return PaymentView.builder()
                .id(PAYMENT_ID.toString())
                .orderId(ORDER_ID.toString())
                .amount(new BigDecimal("1998.00"))
                .currency("INR")
                .method("UPI")
                .status(PaymentStatus.AUTHORIZED.name())
                .provider("simulated")
                .providerReference("sim_abc123")
                .createdAt(Instant.parse("2026-11-11T10:00:00Z"))
                .authorizedAt(Instant.parse("2026-11-11T10:01:00Z"))
                .build();
    }

    /**
     * Reading a payment is scoped to the caller and never cached.
     * <ul>
     *   <li><b>Given</b> a signed-in caller</li>
     *   <li><b>When</b> they read a payment</li>
     *   <li><b>Then</b> it returns 200 with {@code no-store}, and the query carries the caller's id so the
     *       handler can refuse a payment that is not theirs</li>
     * </ul>
     */
    @Test
    void readsAPaymentScopedToTheCaller() {
        when(queryBus.ask(any(GetPaymentQuery.class))).thenReturn(Mono.just(payment()));

        client.mutateWith(mockJwt().jwt(jwt -> jwt.subject(USER_ID.toString())))
                .get().uri("/api/v1/payments/" + PAYMENT_ID)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().cacheControl(org.springframework.http.CacheControl.noStore())
                .expectBody().jsonPath("$.status").isEqualTo("AUTHORIZED");

        ArgumentCaptor<GetPaymentQuery> captor = ArgumentCaptor.forClass(GetPaymentQuery.class);
        verify(queryBus).ask(captor.capture());
        assertThat(captor.getValue().paymentId()).isEqualTo(PAYMENT_ID);
        assertThat(captor.getValue().userId()).isEqualTo(USER_ID);
    }

    /**
     * Confirming takes nothing from the client but the payment id.
     * <ul>
     *   <li><b>Given</b> a signed-in caller</li>
     *   <li><b>When</b> they confirm a payment</li>
     *   <li><b>Then</b> the command carries only the payment and the caller - the outcome is read from the
     *       provider, so a client cannot declare itself paid</li>
     * </ul>
     */
    @Test
    void confirmsWithoutTrustingTheClientForTheOutcome() {
        when(commandBus.dispatch(any(ConfirmPaymentCommand.class))).thenReturn(Mono.just(payment()));

        client.mutateWith(mockJwt().jwt(jwt -> jwt.subject(USER_ID.toString()))).mutateWith(csrf())
                .post().uri("/api/v1/payments/" + PAYMENT_ID + "/confirm")
                .exchange().expectStatus().isOk();

        ArgumentCaptor<ConfirmPaymentCommand> captor = ArgumentCaptor.forClass(ConfirmPaymentCommand.class);
        verify(commandBus).dispatch(captor.capture());
        assertThat(captor.getValue().paymentId()).isEqualTo(PAYMENT_ID);
        assertThat(captor.getValue().userId()).isEqualTo(USER_ID);
    }

    /**
     * The provider callback needs no session and no CSRF token.
     * <ul>
     *   <li><b>Given</b> no access token and no CSRF token, as a provider's server would have</li>
     *   <li><b>When</b> a signed callback is posted</li>
     *   <li><b>Then</b> it is 204, and the command receives the body byte for byte together with the
     *       signature header - re-serialising the body would break the signature</li>
     * </ul>
     */
    @Test
    void acceptsAProviderCallbackWithoutASessionOrCsrfToken() {
        when(commandBus.dispatch(any(HandlePaymentWebhookCommand.class))).thenReturn(Mono.just(true));
        String payload = "{\"event\":\"payment.authorized\",\"providerReference\":\"sim_abc123\"}";

        client.post().uri("/api/v1/payments/webhook")
                .header(PaymentController.SIGNATURE_HEADER, "abcdef0123456789")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(payload)
                .exchange().expectStatus().isNoContent();

        ArgumentCaptor<HandlePaymentWebhookCommand> captor =
                ArgumentCaptor.forClass(HandlePaymentWebhookCommand.class);
        verify(commandBus).dispatch(captor.capture());
        assertThat(captor.getValue().payload()).isEqualTo(payload);
        assertThat(captor.getValue().signature()).isEqualTo("abcdef0123456789");
    }

    /**
     * A callback with no signature still reaches the handler, which is what fails it closed.
     * <ul>
     *   <li><b>Given</b> a callback with no signature header</li>
     *   <li><b>When</b> it is posted</li>
     *   <li><b>Then</b> the command is dispatched with a {@code null} signature rather than the request
     *       being rejected by binding, so the refusal comes from the one place that verifies signatures</li>
     * </ul>
     */
    @Test
    void passesAnUnsignedCallbackToTheHandlerToBeRefused() {
        when(commandBus.dispatch(any(HandlePaymentWebhookCommand.class))).thenReturn(Mono.just(true));

        client.post().uri("/api/v1/payments/webhook")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                .exchange().expectStatus().isNoContent();

        ArgumentCaptor<HandlePaymentWebhookCommand> captor =
                ArgumentCaptor.forClass(HandlePaymentWebhookCommand.class);
        verify(commandBus).dispatch(captor.capture());
        assertThat(captor.getValue().signature()).isNull();
    }

    /**
     * A webhook command never prints the body or the signature.
     * <ul>
     *   <li><b>Given</b> a callback command carrying a body and a valid signature</li>
     *   <li><b>When</b> it is rendered as a string, as the logging aspect does at DEBUG</li>
     *   <li><b>Then</b> neither the body nor the signature appears, because a valid signature is a
     *       credential</li>
     * </ul>
     */
    @Test
    void keepsTheCallbackBodyAndSignatureOutOfLogs() {
        String rendered = new HandlePaymentWebhookCommand("{\"providerReference\":\"sim_secret\"}",
                "abcdef0123456789").toString();

        assertThat(rendered).doesNotContain("sim_secret").doesNotContain("abcdef0123456789");
    }

    /**
     * Everything except the callback needs a token.
     * <ul>
     *   <li><b>Given</b> no access token</li>
     *   <li><b>When</b> a payment is read and confirmed</li>
     *   <li><b>Then</b> both are 401 and nothing is dispatched</li>
     * </ul>
     */
    @Test
    void requiresSignInForEverythingButTheCallback() {
        client.get().uri("/api/v1/payments/" + PAYMENT_ID).exchange().expectStatus().isUnauthorized();
        client.mutateWith(csrf()).post().uri("/api/v1/payments/" + PAYMENT_ID + "/confirm")
                .exchange().expectStatus().isUnauthorized();

        verifyNoInteractions(commandBus, queryBus);
    }
}
