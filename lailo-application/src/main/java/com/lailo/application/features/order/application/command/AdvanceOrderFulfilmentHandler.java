package com.lailo.application.features.order.application.command;

import com.lailo.application.common.cqrs.CommandHandler;
import com.lailo.application.common.outbox.OutboxPublisher;
import com.lailo.application.features.order.application.OrderErrors;
import com.lailo.application.features.order.domain.Order;
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
 * Drives the fulfilment pipeline. Each move is allowed only from the state before it, so the audit trail on
 * the order is always a complete history rather than a last-known-state guess, and "shipped" can be trusted
 * to mean a courier really has the parcel.
 *
 * <p>Shipping is what moves an order out of the "to be shipped" tab and into "shipped", which is why the
 * tracking details are required at exactly that step.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdvanceOrderFulfilmentHandler
        implements CommandHandler<AdvanceOrderFulfilmentCommand, Boolean> {

    private final OrderRepository orders;
    private final OutboxPublisher outbox;
    private final TransactionalOperator tx;
    private final Clock clock;

    /**
     * Applies the move if it is legal from the order's current state.
     *
     * @param cmd the request
     * @return {@code true} when the order moved, {@code false} when it was already in that state, or
     *         {@code ORDER_NOT_FOUND} / {@code ORDER_STATUS_INVALID}
     */
    @Override
    public Mono<Boolean> handle(AdvanceOrderFulfilmentCommand cmd) {
        Instant now = Instant.now(clock);
        return Mono.defer(() -> orders.findById(cmd.orderId())
                .switchIfEmpty(Mono.error(OrderErrors.notFound(cmd.orderId())))
                .flatMap(order -> {
                    if (order.getStatus() == cmd.target()) {
                        return Mono.just(false);
                    }
                    if (!isLegal(order.getStatus(), cmd.target())) {
                        return Mono.error(OrderErrors.invalidTransition(order.getStatus().name(),
                                cmd.target().name().toLowerCase()));
                    }
                    return advance(order, cmd, now).thenReturn(true);
                }));
    }

    /**
     * Whether a move is part of the pipeline.
     *
     * @param from the order's current state
     * @param to the state asked for
     * @return {@code true} when the move is allowed
     */
    private static boolean isLegal(OrderStatus from, OrderStatus to) {
        return switch (to) {
            case PACKED -> from == OrderStatus.CONFIRMED;
            // A small parcel often skips a separate packing step, so shipping straight from confirmed is fine.
            case SHIPPED -> from == OrderStatus.CONFIRMED || from == OrderStatus.PACKED;
            case DELIVERED -> from == OrderStatus.SHIPPED;
            default -> false;
        };
    }

    /**
     * Writes the new state and the matching event.
     *
     * @param order the order as stored
     * @param cmd the request
     * @param now time of the move
     * @return completion signal
     */
    private Mono<Void> advance(Order order, AdvanceOrderFulfilmentCommand cmd, Instant now) {
        return switch (cmd.target()) {
            case PACKED -> save(order.pack(now), OrderEvents.ORDER_PACKED);
            case SHIPPED -> ship(order, cmd, now);
            case DELIVERED -> save(order.deliver(now), OrderEvents.ORDER_DELIVERED);
            default -> Mono.error(new IllegalStateException("Unreachable: " + cmd.target()));
        };
    }

    /**
     * Writes the dispatch, which carries the tracking details the shopper is waiting for.
     *
     * @param order the order as stored
     * @param cmd the request
     * @param now dispatch time
     * @return completion signal
     */
    private Mono<Void> ship(Order order, AdvanceOrderFulfilmentCommand cmd, Instant now) {
        Order shipped = order.ship(cmd.courierName(), cmd.trackingNumber(), cmd.expectedDeliveryDate(), now);
        return orders.save(shipped)
                .flatMap(saved -> outbox.append(OrderEvents.TOPIC, OrderEvents.AGGREGATE,
                        saved.getId().toString(), OrderEvents.ORDER_SHIPPED,
                        OrderEvents.OrderShipped.of(saved)))
                .as(tx::transactional)
                .doOnSuccess(ignored -> log.info("Order {} shipped via {} ({})", order.getOrderNumber(),
                        cmd.courierName(), cmd.trackingNumber()))
                .then();
    }

    /**
     * Writes a plain status move and its event.
     *
     * @param moved the order after the transition
     * @param eventType event name to publish
     * @return completion signal
     */
    private Mono<Void> save(Order moved, String eventType) {
        return orders.save(moved)
                .flatMap(saved -> outbox.append(OrderEvents.TOPIC, OrderEvents.AGGREGATE,
                        saved.getId().toString(), eventType, OrderEvents.OrderStatusChanged.of(saved)))
                .as(tx::transactional)
                .then();
    }

    /**
     * Serves {@link AdvanceOrderFulfilmentCommand}.
     *
     * @return the command class
     */
    @Override
    public Class<AdvanceOrderFulfilmentCommand> commandType() {
        return AdvanceOrderFulfilmentCommand.class;
    }
}
