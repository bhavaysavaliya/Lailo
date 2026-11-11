package com.lailo.application.features.order.application.command;

import com.lailo.application.common.cqrs.CommandHandler;
import com.lailo.application.common.outbox.OutboxPublisher;
import com.lailo.application.features.order.application.OrderErrors;
import com.lailo.application.features.order.domain.Order;
import com.lailo.application.features.order.domain.OrderStatus;
import com.lailo.application.features.order.domain.event.OrderEvents;
import com.lailo.application.features.order.infrastructure.persistence.OrderItemRepository;
import com.lailo.application.features.order.infrastructure.persistence.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;

/**
 * Cancels an order and announces it. Releasing the reserved units and refunding an authorised payment are
 * <em>not</em> done here: they are the checkout saga's compensating steps, driven by the
 * {@code OrderCancelled} event this publishes. Keeping them out means a cancellation commits even if the
 * gateway is down, and the refund is retried until it succeeds.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CancelOrderHandler implements CommandHandler<CancelOrderCommand, Boolean> {

    private final OrderRepository orders;
    private final OrderItemRepository orderItems;
    private final OutboxPublisher outbox;
    private final TransactionalOperator tx;
    private final Clock clock;

    /**
     * Cancels the order if it still can be.
     *
     * @param cmd the request
     * @return {@code true} when this call cancelled it, {@code false} when it was already cancelled, or
     *         {@code ORDER_NOT_FOUND} / {@code ORDER_NOT_CANCELLABLE}
     */
    @Override
    public Mono<Boolean> handle(CancelOrderCommand cmd) {
        Instant now = Instant.now(clock);
        return Mono.defer(() -> orders.findById(cmd.orderId())
                .filter(order -> cmd.userId() == null || order.getUserId().equals(cmd.userId()))
                .switchIfEmpty(Mono.error(OrderErrors.notFound(cmd.orderId())))
                .flatMap(order -> {
                    if (order.getStatus() == OrderStatus.CANCELLED) {
                        return Mono.just(false);
                    }
                    if (!order.isCancellable()) {
                        return Mono.error(OrderErrors.notCancellable(order.getStatus().name()));
                    }
                    return cancel(order, cmd, now);
                }));
    }

    /**
     * Writes the cancellation and its event, with the lines on the event so compensation needs no further
     * read.
     *
     * @param order the order as stored
     * @param cmd the request
     * @param now cancellation time
     * @return always {@code true}
     */
    private Mono<Boolean> cancel(Order order, CancelOrderCommand cmd, Instant now) {
        return orderItems.findByOrderId(order.getId())
                .collectList()
                .flatMap(lines -> orders.save(order.cancel(cmd.reason(), now))
                        .flatMap(saved -> outbox.append(OrderEvents.TOPIC, OrderEvents.AGGREGATE,
                                saved.getId().toString(), OrderEvents.ORDER_CANCELLED,
                                OrderEvents.OrderCancelled.of(saved, lines))))
                .as(tx::transactional)
                .doOnSuccess(ignored -> log.info("Order {} cancelled ({})", order.getOrderNumber(), cmd.reason()))
                .thenReturn(true);
    }

    /**
     * Serves {@link CancelOrderCommand}.
     *
     * @return the command class
     */
    @Override
    public Class<CancelOrderCommand> commandType() {
        return CancelOrderCommand.class;
    }
}
