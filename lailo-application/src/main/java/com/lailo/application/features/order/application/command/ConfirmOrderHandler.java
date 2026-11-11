package com.lailo.application.features.order.application.command;

import com.lailo.application.common.cqrs.CommandHandler;
import com.lailo.application.common.outbox.OutboxPublisher;
import com.lailo.application.features.order.application.OrderErrors;
import com.lailo.application.features.order.domain.OrderStatus;
import com.lailo.application.features.order.domain.event.OrderEvents;
import com.lailo.application.features.order.infrastructure.persistence.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;

/**
 * Confirms a paid order.
 *
 * <p>A cancelled order is never confirmed, however late the authorisation arrives: that pairing is a refund,
 * not a sale, and the saga handles it as compensation. Confirming an already confirmed order is a no-op, so
 * a redelivered {@code PaymentAuthorized} changes nothing.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConfirmOrderHandler implements CommandHandler<ConfirmOrderCommand, Boolean> {

    private final OrderRepository orders;
    private final OutboxPublisher outbox;
    private final TransactionalOperator tx;
    private final Clock clock;

    /**
     * Moves the order to {@code CONFIRMED} and announces it.
     *
     * @param cmd the request
     * @return {@code true} when this call confirmed the order, {@code false} when it was already past that
     *         point, or {@code ORDER_NOT_FOUND}
     */
    @Override
    public Mono<Boolean> handle(ConfirmOrderCommand cmd) {
        Instant now = Instant.now(clock);
        return Mono.defer(() -> orders.findById(cmd.orderId())
                .switchIfEmpty(Mono.error(OrderErrors.notFound(cmd.orderId())))
                .flatMap(order -> {
                    if (order.getStatus() != OrderStatus.PENDING_PAYMENT) {
                        log.debug("Order {} is {} - not confirming", order.getOrderNumber(), order.getStatus());
                        return Mono.just(false);
                    }
                    return orders.save(order.confirm(now))
                            .flatMap(saved -> outbox.append(OrderEvents.TOPIC, OrderEvents.AGGREGATE,
                                    saved.getId().toString(), OrderEvents.ORDER_CONFIRMED,
                                    OrderEvents.OrderStatusChanged.of(saved)))
                            .as(tx::transactional)
                            .thenReturn(true);
                }));
    }

    /**
     * Serves {@link ConfirmOrderCommand}.
     *
     * @return the command class
     */
    @Override
    public Class<ConfirmOrderCommand> commandType() {
        return ConfirmOrderCommand.class;
    }
}
