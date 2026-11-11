package com.lailo.application.features.order.application.command;

import com.lailo.application.common.cqrs.CommandHandler;
import com.lailo.application.features.order.infrastructure.persistence.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;

/**
 * Records which payment attempt an order is waiting on.
 *
 * <p>No event is published: the {@code PaymentInitiated} event already carries the pairing, and emitting a
 * second event for the same fact would mean two ways for the read model to disagree with itself. Idempotent,
 * and silent about a missing order, because it runs from a retryable consumer.</p>
 */
@Component
@RequiredArgsConstructor
public class AttachPaymentToOrderHandler implements CommandHandler<AttachPaymentToOrderCommand, Boolean> {

    private final OrderRepository orders;
    private final Clock clock;

    /**
     * Stores the pairing.
     *
     * @param cmd the request
     * @return {@code true} when the order was updated, {@code false} when it was already paired or missing
     */
    @Override
    public Mono<Boolean> handle(AttachPaymentToOrderCommand cmd) {
        return orders.findById(cmd.orderId())
                .flatMap(order -> cmd.paymentId().equals(order.getPaymentId())
                        ? Mono.just(false)
                        : orders.save(order.attachPayment(cmd.paymentId(), Instant.now(clock)))
                        .thenReturn(true))
                .defaultIfEmpty(false);
    }

    /**
     * Serves {@link AttachPaymentToOrderCommand}.
     *
     * @return the command class
     */
    @Override
    public Class<AttachPaymentToOrderCommand> commandType() {
        return AttachPaymentToOrderCommand.class;
    }
}
