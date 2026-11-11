package com.lailo.application.features.payment.application.command;

import com.lailo.application.common.cqrs.CommandHandler;
import com.lailo.application.common.outbox.OutboxPublisher;
import com.lailo.application.features.payment.application.PaymentErrors;
import com.lailo.application.features.payment.domain.Payment;
import com.lailo.application.features.payment.domain.PaymentStatus;
import com.lailo.application.features.payment.domain.event.PaymentEvents;
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
 * Refunds an authorised payment.
 *
 * <p>Idempotent in both directions, because it runs as a compensating action: an already refunded attempt
 * reports success, and an attempt that was never authorised (so nothing was taken) does too. Only a state
 * that means money is stuck somewhere raises an error.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RefundPaymentHandler implements CommandHandler<RefundPaymentCommand, Boolean> {

    private final PaymentRepository payments;
    private final PaymentGateway gateway;
    private final OutboxPublisher outbox;
    private final TransactionalOperator tx;
    private final Clock clock;

    /**
     * Refunds the attempt if there is anything to refund.
     *
     * @param cmd the request
     * @return {@code true} when money was returned, {@code false} when there was nothing to return
     */
    @Override
    public Mono<Boolean> handle(RefundPaymentCommand cmd) {
        return payments.findById(cmd.paymentId())
                .switchIfEmpty(Mono.error(PaymentErrors.notFound(cmd.paymentId())))
                .flatMap(payment -> switch (payment.getStatus()) {
                    case AUTHORIZED -> refund(payment);
                    case REFUNDED -> Mono.just(false);
                    case PENDING, FAILED -> Mono.just(false);
                });
    }

    /**
     * Calls the gateway, then records the refund.
     *
     * @param payment the authorised attempt
     * @return {@code true} when the provider accepted the refund
     */
    private Mono<Boolean> refund(Payment payment) {
        return gateway.refund(payment.getProviderReference(), payment.getAmount())
                .flatMap(outcome -> {
                    if (!outcome.approved()) {
                        // The money is still with the provider: fail loudly so the saga records it and an
                        // operator can finish the refund by hand rather than it being silently dropped.
                        return Mono.error(PaymentErrors.notRefundable(PaymentStatus.AUTHORIZED.name()));
                    }
                    Instant now = Instant.now(clock);
                    return payments.save(payment.refund(now))
                            .flatMap(saved -> outbox.append(PaymentEvents.TOPIC, PaymentEvents.AGGREGATE,
                                            saved.getOrderId().toString(), PaymentEvents.PAYMENT_REFUNDED,
                                            PaymentEvents.PaymentRefunded.of(saved))
                                    .thenReturn(saved))
                            .as(tx::transactional)
                            .doOnNext(saved -> log.info("Refunded payment {} for order {}", saved.getId(),
                                    saved.getOrderId()))
                            .thenReturn(true);
                });
    }

    /**
     * Serves {@link RefundPaymentCommand}.
     *
     * @return the command class
     */
    @Override
    public Class<RefundPaymentCommand> commandType() {
        return RefundPaymentCommand.class;
    }
}
