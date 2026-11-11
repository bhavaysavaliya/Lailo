package com.lailo.application.features.payment.application.command;

import com.lailo.application.common.cqrs.CommandHandler;
import com.lailo.application.common.outbox.OutboxPublisher;
import com.lailo.application.features.payment.application.query.PaymentView;
import com.lailo.application.features.payment.domain.Payment;
import com.lailo.application.features.payment.domain.event.PaymentEvents;
import com.lailo.application.features.payment.infrastructure.gateway.GatewayIntent;
import com.lailo.application.features.payment.infrastructure.gateway.GatewayIntentRequest;
import com.lailo.application.features.payment.infrastructure.gateway.PaymentGateway;
import com.lailo.application.features.payment.infrastructure.persistence.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Creates a payment attempt.
 *
 * <p>The gateway is called <em>before</em> the transaction opens, not inside it: a database transaction held
 * across a network call to a third party is how connection pools get exhausted, and the row is worth less
 * than the reference it has to carry. If the insert then fails, the provider is left with an intent nobody
 * uses, which expires on its own.</p>
 *
 * <p>Cash on delivery takes no gateway at all - there is nothing to authorise online - so it is authorised
 * on the spot and the saga continues straight to confirmation.</p>
 */
@Component
@RequiredArgsConstructor
public class InitiatePaymentHandler implements CommandHandler<InitiatePaymentCommand, PaymentView> {

    private final PaymentRepository payments;
    private final PaymentGateway gateway;
    private final OutboxPublisher outbox;
    private final TransactionalOperator tx;
    private final Clock clock;

    /**
     * Opens the attempt and appends its events.
     *
     * @param cmd the request
     * @return the attempt, with a checkout link for the online methods
     */
    @Override
    public Mono<PaymentView> handle(InitiatePaymentCommand cmd) {
        Instant now = Instant.now(clock);
        UUID paymentId = UUID.randomUUID();
        Payment pending = Payment.initiate(paymentId, cmd.orderId(), cmd.userId(), cmd.amount(),
                cmd.currency(), cmd.method(), gateway.provider(), now);

        if (!cmd.method().requiresOnlineAuthorization()) {
            return storeCashOnDelivery(pending, now);
        }
        return gateway.createIntent(GatewayIntentRequest.builder()
                        .paymentId(paymentId)
                        .orderNumber(cmd.orderNumber())
                        .amount(cmd.amount())
                        .currency(cmd.currency())
                        .method(cmd.method())
                        .build())
                .flatMap(intent -> storeOnline(pending, intent));
    }

    /**
     * Stores an online attempt with the gateway's reference and announces it.
     *
     * @param pending the attempt to store
     * @param intent what the gateway returned
     * @return the attempt, with the checkout link
     */
    private Mono<PaymentView> storeOnline(Payment pending, GatewayIntent intent) {
        return payments.save(pending.withProviderReference(intent.providerReference()))
                .flatMap(saved -> append(PaymentEvents.PAYMENT_INITIATED, saved,
                        PaymentEvents.PaymentInitiated.of(saved)).thenReturn(saved))
                .as(tx::transactional)
                .map(saved -> PaymentView.of(saved, intent.checkoutUrl()));
    }

    /**
     * Stores a cash-on-delivery attempt, already authorised, and announces both events in one transaction so
     * the saga sees the authorisation even if this instance dies immediately after.
     *
     * @param pending the attempt to store
     * @param now creation and authorisation time
     * @return the authorised attempt
     */
    private Mono<PaymentView> storeCashOnDelivery(Payment pending, Instant now) {
        // The authorisation is stamped a millisecond after the creation, and deliberately so. Both events
        // describe the same aggregate and the read model projects them under an "ignore anything not newer
        // than what I have" guard; MongoDB stores instants to millisecond precision, so two events sharing a
        // millisecond would make the second one look stale and the order would sit at PENDING for ever.
        Payment authorized = pending.authorize("cod-" + pending.getId(), now.plusMillis(1));
        return payments.save(authorized)
                .flatMap(saved -> append(PaymentEvents.PAYMENT_INITIATED, saved,
                        PaymentEvents.PaymentInitiated.of(saved))
                        .then(append(PaymentEvents.PAYMENT_AUTHORIZED, saved,
                                PaymentEvents.PaymentAuthorized.of(saved)))
                        .thenReturn(saved))
                .as(tx::transactional)
                .map(PaymentView::of);
    }

    /**
     * Appends one payment event, keyed by order id so every event about an order stays ordered.
     *
     * @param eventType event name
     * @param payment the attempt the event is about
     * @param event the payload
     * @return completion signal
     */
    private Mono<Void> append(String eventType, Payment payment, Object event) {
        return outbox.append(PaymentEvents.TOPIC, PaymentEvents.AGGREGATE,
                payment.getOrderId().toString(), eventType, event).then();
    }

    /**
     * Serves {@link InitiatePaymentCommand}.
     *
     * @return the command class
     */
    @Override
    public Class<InitiatePaymentCommand> commandType() {
        return InitiatePaymentCommand.class;
    }
}
