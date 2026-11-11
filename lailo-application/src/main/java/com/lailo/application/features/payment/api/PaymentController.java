package com.lailo.application.features.payment.api;

import com.lailo.application.common.cqrs.CommandBus;
import com.lailo.application.common.cqrs.QueryBus;
import com.lailo.application.features.payment.application.command.ConfirmPaymentCommand;
import com.lailo.application.features.payment.application.command.HandlePaymentWebhookCommand;
import com.lailo.application.features.payment.application.query.GetPaymentQuery;
import com.lailo.application.features.payment.application.query.PaymentView;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Payment routes.
 *
 * <p>There is no "create payment" route: a payment only ever comes into being because the checkout saga
 * decided an order was ready for one. The client's part is to complete the attempt with the gateway and then
 * tell Lailo to settle it.</p>
 */
@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
public class PaymentController {

    /** Header the provider signs its callbacks with. */
    public static final String SIGNATURE_HEADER = "X-Lailo-Signature";

    private final CommandBus commandBus;
    private final QueryBus queryBus;

    /**
     * Reads one of the caller's payment attempts.
     *
     * @param jwt the verified access token; its subject is the user id
     * @param paymentId the attempt to read
     * @return the attempt, or 404 {@code PAYMENT_NOT_FOUND}
     */
    @GetMapping("/{paymentId}")
    public Mono<ResponseEntity<PaymentView>> get(@AuthenticationPrincipal Jwt jwt,
                                                 @PathVariable UUID paymentId) {
        return queryBus.ask(new GetPaymentQuery(paymentId, UUID.fromString(jwt.getSubject())))
                .map(payment -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(payment));
    }

    /**
     * Settles an attempt the shopper has completed at the gateway. The body carries nothing: the outcome is
     * read from the provider, never from the client.
     *
     * @param jwt the verified access token
     * @param paymentId the attempt to settle
     * @return the settled attempt, or 402 {@code PAYMENT_DECLINED} / 409 {@code PAYMENT_NOT_PENDING}
     */
    @PostMapping("/{paymentId}/confirm")
    public Mono<ResponseEntity<PaymentView>> confirm(@AuthenticationPrincipal Jwt jwt,
                                                     @PathVariable UUID paymentId) {
        return commandBus.dispatch(new ConfirmPaymentCommand(paymentId, UUID.fromString(jwt.getSubject())))
                .map(payment -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(payment));
    }

    /**
     * Receives a provider callback. Public and CSRF-exempt by necessity - the caller is a server, not a
     * browser - so the signature is the only thing that authenticates it.
     *
     * @param payload raw request body, taken as a string so the signature is checked over exactly the bytes
     *        that were sent
     * @param signature the provider's signature header
     * @return 204 No Content once accounted for, or 401 {@code WEBHOOK_SIGNATURE_INVALID}
     */
    @PostMapping("/webhook")
    public Mono<ResponseEntity<Void>> webhook(@RequestBody String payload,
                                              @RequestHeader(name = SIGNATURE_HEADER, required = false)
                                              String signature) {
        return commandBus.dispatch(new HandlePaymentWebhookCommand(payload, signature))
                .thenReturn(ResponseEntity.noContent().build());
    }
}
