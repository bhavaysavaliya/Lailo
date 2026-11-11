package com.lailo.application.features.payment.application.command;

import com.lailo.application.common.cqrs.CommandHandler;
import com.lailo.application.common.outbox.OutboxPublisher;
import com.lailo.application.features.payment.application.PaymentErrors;
import com.lailo.application.features.payment.application.query.PaymentView;
import com.lailo.application.features.payment.domain.Payment;
import com.lailo.application.features.payment.domain.event.PaymentEvents;
import com.lailo.application.features.payment.infrastructure.gateway.GatewayOutcome;
import com.lailo.application.features.payment.infrastructure.gateway.PaymentGateway;
import com.lailo.application.features.payment.infrastructure.persistence.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;

/**
 * Settles a payment attempt against the gateway.
 *
 * <p>Confirming an already authorised attempt returns it unchanged rather than failing: the client, the
 * webhook and a retry all call this, often at once, and only one of them can be first.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConfirmPaymentHandler implements CommandHandler<ConfirmPaymentCommand, PaymentView> {

    private final PaymentRepository payments;
    private final PaymentGateway gateway;
    private final OutboxPublisher outbox;
    private final TransactionalOperator tx;
    private final Clock clock;

    /**
     * Loads the attempt, asks the gateway, and stores the outcome.
     *
     * @param cmd the request
     * @return the settled attempt, or {@code PAYMENT_NOT_FOUND} / {@code PAYMENT_NOT_PENDING} /
     *         402 {@code PAYMENT_DECLINED}
     */
    @Override
    public Mono<PaymentView> handle(ConfirmPaymentCommand cmd) {
        return payments.findById(cmd.paymentId())
                .filter(payment -> cmd.userId() == null || payment.getUserId().equals(cmd.userId()))
                .switchIfEmpty(Mono.error(PaymentErrors.notFound(cmd.paymentId())))
                .flatMap(this::settle);
    }

    /**
     * Decides what to do with the attempt in the state it is in.
     *
     * @param payment the attempt as stored
     * @return the settled attempt
     */
    private Mono<PaymentView> settle(Payment payment) {
        if (payment.isAuthorized()) {
            return Mono.just(PaymentView.of(payment)); // already settled: idempotent, not an error
        }
        if (!payment.isPending()) {
            return Mono.error(PaymentErrors.notPending(payment.getStatus().name()));
        }
        return gateway.confirm(payment.getProviderReference())
                .flatMap(outcome -> outcome.approved()
                        ? authorize(payment, outcome)
                        : decline(payment, outcome));
    }

    /**
     * Records an approval and announces it, which is what the saga turns into a confirmed order.
     *
     * @param payment the attempt as stored
     * @param outcome the gateway's decision
     * @return the authorised attempt
     */
    private Mono<PaymentView> authorize(Payment payment, GatewayOutcome outcome) {
        Instant now = Instant.now(clock);
        Payment authorized = payment.authorize(
                outcome.providerReference() == null ? payment.getProviderReference() : outcome.providerReference(),
                now);
        return payments.save(authorized)
                .flatMap(saved -> outbox.append(PaymentEvents.TOPIC, PaymentEvents.AGGREGATE,
                                saved.getOrderId().toString(), PaymentEvents.PAYMENT_AUTHORIZED,
                                PaymentEvents.PaymentAuthorized.of(saved))
                        .thenReturn(saved))
                .as(tx::transactional)
                .map(PaymentView::of);
    }

    /**
     * Records a decline, then reports it.
     *
     * <p>The write is committed in its own transaction <em>before</em> the error is signalled: an error
     * raised inside the transaction would roll back the very record the saga needs in order to cancel the
     * order and release its stock.</p>
     *
     * @param payment the attempt as stored
     * @param outcome the gateway's decision
     * @return always an error signal
     */
    private Mono<PaymentView> decline(Payment payment, GatewayOutcome outcome) {
        Instant now = Instant.now(clock);
        Payment failed = payment.fail(outcome.failureReason(), now);
        return payments.save(failed)
                .flatMap(saved -> outbox.append(PaymentEvents.TOPIC, PaymentEvents.AGGREGATE,
                                saved.getOrderId().toString(), PaymentEvents.PAYMENT_FAILED,
                                PaymentEvents.PaymentFailed.of(saved))
                        .thenReturn(saved))
                .as(tx::transactional)
                .doOnNext(saved -> log.info("Payment {} for order {} declined", saved.getId(), saved.getOrderId()))
                .flatMap(saved -> Mono.error(PaymentErrors.declined(saved.getFailureReason())));
    }

    /**
     * Serves {@link ConfirmPaymentCommand}.
     *
     * @return the command class
     */
    @Override
    public Class<ConfirmPaymentCommand> commandType() {
        return ConfirmPaymentCommand.class;
    }
}
