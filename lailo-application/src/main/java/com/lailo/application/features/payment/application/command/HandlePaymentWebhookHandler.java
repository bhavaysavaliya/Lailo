package com.lailo.application.features.payment.application.command;

import com.lailo.application.common.cqrs.CommandBus;
import com.lailo.application.common.cqrs.CommandHandler;
import com.lailo.application.common.web.ApiException;
import com.lailo.application.features.payment.application.PaymentErrors;
import com.lailo.application.features.payment.application.query.PaymentView;
import com.lailo.application.features.payment.infrastructure.gateway.PaymentGateway;
import com.lailo.application.features.payment.infrastructure.persistence.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

/**
 * Handles provider callbacks, which are what makes checkout survive a shopper closing the tab mid-payment.
 *
 * <p>Two rules make this safe. First, the signature is verified before anything else and failure is closed:
 * an unsigned callback is an attacker. Second, the outcome in the body is used only for logging - the
 * handler re-asks the gateway through {@link ConfirmPaymentCommand}, so a forged or replayed body cannot
 * mark an order paid.</p>
 *
 * <p>Anything already settled, unknown or declined is answered with success, because a provider that gets an
 * error retries the callback for hours. Only a bad signature is refused.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HandlePaymentWebhookHandler implements CommandHandler<HandlePaymentWebhookCommand, Boolean> {

    private final PaymentGateway gateway;
    private final PaymentRepository payments;
    private final CommandBus commandBus;
    private final JsonMapper jsonMapper;

    /**
     * Verifies, resolves and re-confirms.
     *
     * @param cmd the callback
     * @return {@code true} once the callback has been accounted for, or
     *         401 {@code WEBHOOK_SIGNATURE_INVALID}
     */
    @Override
    public Mono<Boolean> handle(HandlePaymentWebhookCommand cmd) {
        return Mono.defer(() -> {
            if (!gateway.verifySignature(cmd.payload(), cmd.signature())) {
                return Mono.error(PaymentErrors.webhookSignatureInvalid());
            }
            Callback callback = parse(cmd.payload());
            if (callback == null || callback.providerReference() == null) {
                log.warn("Ignoring payment callback without a provider reference");
                return Mono.just(true);
            }
            return payments.findByProviderReference(callback.providerReference())
                    .flatMap(payment -> reconfirm(payment.getId(), callback))
                    .defaultIfEmpty(true)
                    .doOnSubscribe(s -> log.debug("Payment callback {} for {}", callback.event(),
                            callback.providerReference()));
        });
    }

    /**
     * Re-asks the gateway about the attempt and swallows the outcomes a provider must not be told about.
     *
     * @param paymentId the attempt the callback refers to
     * @param callback the parsed body, for logging
     * @return always {@code true}
     */
    private Mono<Boolean> reconfirm(UUID paymentId, Callback callback) {
        Mono<PaymentView> confirm = commandBus.dispatch(new ConfirmPaymentCommand(paymentId, null));
        return confirm
                .thenReturn(true)
                .onErrorResume(ApiException.class, ex -> {
                    log.info("Payment callback {} for {} left the payment as it was: {}",
                            callback.event(), paymentId, ex.getCode());
                    return Mono.just(true);
                });
    }

    /**
     * Parses the callback body, tolerating anything unexpected: a provider adding a field must not break the
     * endpoint.
     *
     * @param payload raw request body
     * @return the parsed callback, or {@code null} when it cannot be read
     */
    private Callback parse(String payload) {
        try {
            return jsonMapper.readValue(payload, Callback.class);
        } catch (RuntimeException ex) {
            log.warn("Unreadable payment callback body: {}", ex.getMessage());
            return null;
        }
    }

    /**
     * The part of a provider callback Lailo reads.
     *
     * @param event provider's event name, kept for logging only
     * @param providerReference the gateway's reference for the attempt
     */
    public record Callback(String event, String providerReference) {
    }

    /**
     * Serves {@link HandlePaymentWebhookCommand}.
     *
     * @return the command class
     */
    @Override
    public Class<HandlePaymentWebhookCommand> commandType() {
        return HandlePaymentWebhookCommand.class;
    }
}
