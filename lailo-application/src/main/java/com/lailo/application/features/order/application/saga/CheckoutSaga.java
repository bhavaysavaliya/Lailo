package com.lailo.application.features.order.application.saga;

import com.lailo.application.common.cqrs.CommandBus;
import com.lailo.application.common.saga.SagaStatus;
import com.lailo.application.common.saga.SagaStore;
import com.lailo.application.common.web.ApiException;
import com.lailo.application.features.order.application.command.AttachPaymentToOrderCommand;
import com.lailo.application.features.order.application.command.CancelOrderCommand;
import com.lailo.application.features.order.application.command.ConfirmOrderCommand;
import com.lailo.application.features.order.domain.CancelReason;
import com.lailo.application.features.order.domain.event.OrderEvents;
import com.lailo.application.features.payment.application.command.InitiatePaymentCommand;
import com.lailo.application.features.payment.application.command.RefundPaymentCommand;
import com.lailo.application.features.payment.application.query.PaymentView;
import com.lailo.application.features.payment.domain.PaymentMethod;
import com.lailo.application.features.payment.domain.event.PaymentEvents;
import com.lailo.application.features.product.application.command.ReleaseStockCommand;
import com.lailo.application.features.product.application.command.ReserveStockCommand;
import com.lailo.application.features.product.application.command.StockLine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Orchestrates checkout across three aggregates that cannot share a transaction: the order (Postgres), the
 * product's stock (Postgres, but a different aggregate with its own consistency rules) and the payment (a
 * third party). It reacts to events and issues the next command.
 *
 * <pre>
 *   OrderPlaced        -&gt; reserve stock          -&gt; STOCK_PENDING   -&gt; PAYMENT_PENDING
 *                         (short of stock)       -&gt; cancel order    -&gt; COMPENSATED
 *   PAYMENT_PENDING    -&gt; initiate payment
 *   PaymentInitiated   -&gt; attach payment to the order
 *   PaymentAuthorized  -&gt; confirm order         -&gt; COMPLETED
 *   PaymentFailed      -&gt; cancel order
 *   OrderCancelled     -&gt; release stock, refund -&gt; RELEASING        -&gt; COMPENSATED
 * </pre>
 *
 * <p>Every forward move is a conditional step transition in {@code saga_instances}, so a redelivered event
 * finds the saga already past that step and does nothing. That guard - not the consumer's offset - is what
 * stops a second payment being taken.</p>
 *
 * <p>The commands it issues are the same ones the API uses. The saga adds no business rules of its own; it
 * only decides what happens next, which keeps the rules in one place.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CheckoutSaga {

    /** Saga name in {@code saga_instances}; the correlation id is the order id. */
    public static final String SAGA_TYPE = "Checkout";

    /** Stock has been asked for but not yet confirmed. */
    public static final String STOCK_PENDING = "STOCK_PENDING";
    /** Stock is reserved; the payment is in play. */
    public static final String PAYMENT_PENDING = "PAYMENT_PENDING";
    /** Paid and confirmed: the happy ending. */
    public static final String COMPLETED = "COMPLETED";
    /** Compensation has been claimed by one consumer and is running. */
    public static final String RELEASING = "RELEASING";
    /** Everything that had been done was undone. */
    public static final String COMPENSATED = "COMPENSATED";
    /** Compensation itself failed; money or stock needs an operator. */
    public static final String COMPENSATION_FAILED = "COMPENSATION_FAILED";

    private final SagaStore sagas;
    private final CommandBus commandBus;

    /**
     * Starts the saga and reserves stock.
     *
     * @param e the event
     * @return completion signal
     */
    public Mono<Void> on(OrderEvents.OrderPlaced e) {
        return sagas.start(SAGA_TYPE, e.orderId(), STOCK_PENDING)
                .flatMap(started -> started ? reserveStock(e) : Mono.empty());
    }

    /**
     * Records which payment attempt the order is waiting on.
     *
     * @param e the event
     * @return completion signal
     */
    public Mono<Void> on(PaymentEvents.PaymentInitiated e) {
        Mono<Boolean> attach = commandBus.dispatch(new AttachPaymentToOrderCommand(
                UUID.fromString(e.orderId()), UUID.fromString(e.paymentId())));
        return attach.then();
    }

    /**
     * Confirms the order once the money is secured.
     *
     * @param e the event
     * @return completion signal
     */
    public Mono<Void> on(PaymentEvents.PaymentAuthorized e) {
        return sagas.transition(SAGA_TYPE, e.orderId(), PAYMENT_PENDING, COMPLETED, SagaStatus.COMPLETED)
                .flatMap(applied -> {
                    if (!applied) {
                        return Mono.empty();
                    }
                    Mono<Boolean> confirm = commandBus.dispatch(
                            new ConfirmOrderCommand(UUID.fromString(e.orderId())));
                    return confirm.then();
                });
    }

    /**
     * Cancels the order when the gateway declines. Compensation is not run here: cancelling publishes
     * {@code OrderCancelled}, and that is the single path through which stock and money are undone, whoever
     * triggered the cancellation.
     *
     * @param e the event
     * @return completion signal
     */
    public Mono<Void> on(PaymentEvents.PaymentFailed e) {
        return sagas.recordError(SAGA_TYPE, e.orderId(), "payment failed: " + e.reason())
                .then(cancel(UUID.fromString(e.orderId()), CancelReason.PAYMENT_FAILED));
    }

    /**
     * Undoes what the saga had done: releases the reserved units and refunds an authorised payment.
     *
     * @param e the event
     * @return completion signal
     */
    public Mono<Void> on(OrderEvents.OrderCancelled e) {
        return sagas.transition(SAGA_TYPE, e.orderId(), Set.of(PAYMENT_PENDING, COMPLETED), RELEASING,
                        SagaStatus.COMPENSATING)
                .flatMap(applied -> applied ? compensate(e) : Mono.empty());
    }

    /**
     * Takes the units out of stock, then opens the payment.
     *
     * @param e the event
     * @return completion signal
     */
    private Mono<Void> reserveStock(OrderEvents.OrderPlaced e) {
        Mono<Boolean> reserve = commandBus.dispatch(
                new ReserveStockCommand(e.orderNumber(), stockLines(e.items())));
        return reserve
                .flatMap(ignored -> sagas.transition(SAGA_TYPE, e.orderId(), STOCK_PENDING, PAYMENT_PENDING,
                        SagaStatus.RUNNING))
                .flatMap(applied -> applied ? initiatePayment(e) : Mono.empty())
                .onErrorResume(ApiException.class, ex -> stockUnavailable(e, ex));
    }

    /**
     * Opens the payment attempt the client will complete.
     *
     * @param e the event
     * @return completion signal
     */
    private Mono<Void> initiatePayment(OrderEvents.OrderPlaced e) {
        Mono<PaymentView> initiate = commandBus.dispatch(InitiatePaymentCommand.builder()
                .orderId(UUID.fromString(e.orderId()))
                .orderNumber(e.orderNumber())
                .userId(UUID.fromString(e.userId()))
                .amount(e.totalAmount())
                .currency(e.currency())
                .method(PaymentMethod.valueOf(e.paymentMethod()))
                .build());
        return initiate.then();
    }

    /**
     * Ends the saga when the reservation failed. Nothing was taken, so the order is simply cancelled and
     * there is nothing to undo.
     *
     * @param e the event
     * @param ex why the reservation failed
     * @return completion signal
     */
    private Mono<Void> stockUnavailable(OrderEvents.OrderPlaced e, ApiException ex) {
        log.info("Order {} cannot be fulfilled ({}); cancelling", e.orderNumber(), ex.getCode());
        return sagas.recordError(SAGA_TYPE, e.orderId(), ex.getCode() + ": " + ex.getMessage())
                .then(sagas.transition(SAGA_TYPE, e.orderId(), STOCK_PENDING, COMPENSATED,
                        SagaStatus.COMPENSATED))
                .flatMap(applied -> applied
                        ? cancel(UUID.fromString(e.orderId()), CancelReason.OUT_OF_STOCK)
                        : Mono.empty());
    }

    /**
     * Runs the compensating actions.
     *
     * <p>The step was claimed one-shot before this runs, so a redelivery cannot release the same units
     * twice - a double release would invent stock, which is worse than a stuck saga. The price of that is
     * that a compensation which genuinely fails is not retried automatically: it is recorded as
     * {@code COMPENSATION_FAILED} with its reason, which is what the stuck-saga monitor looks for.</p>
     *
     * @param e the event
     * @return completion signal
     */
    private Mono<Void> compensate(OrderEvents.OrderCancelled e) {
        Mono<Boolean> release = e.items() == null || e.items().isEmpty()
                ? Mono.just(false)
                : commandBus.dispatch(new ReleaseStockCommand(e.orderNumber(), stockLines(e.items())));
        Mono<Boolean> refund = e.paymentId() == null
                ? Mono.just(false)
                : commandBus.dispatch(new RefundPaymentCommand(UUID.fromString(e.paymentId())));

        return release
                .then(refund)
                .then(sagas.transition(SAGA_TYPE, e.orderId(), RELEASING, COMPENSATED, SagaStatus.COMPENSATED))
                .then()
                .onErrorResume(ex -> {
                    log.error("Compensation for order {} failed and needs an operator: {}",
                            e.orderNumber(), ex.getMessage());
                    return sagas.recordError(SAGA_TYPE, e.orderId(), "compensation failed: " + ex.getMessage())
                            .then(sagas.transition(SAGA_TYPE, e.orderId(), RELEASING, COMPENSATION_FAILED,
                                    SagaStatus.FAILED))
                            .then();
                });
    }

    /**
     * Cancels an order, tolerating an order that cannot be cancelled any more - a parcel already with the
     * courier is a return, which this saga does not own.
     *
     * @param orderId the order to cancel
     * @param reason why
     * @return completion signal
     */
    private Mono<Void> cancel(UUID orderId, CancelReason reason) {
        Mono<Boolean> cancel = commandBus.dispatch(new CancelOrderCommand(orderId, null, reason));
        return cancel
                .onErrorResume(ApiException.class, ex -> {
                    log.warn("Could not cancel order {} ({}): {}", orderId, reason, ex.getCode());
                    return Mono.just(false);
                })
                .then();
    }

    /**
     * Converts event lines into stock lines.
     *
     * @param items the order's lines
     * @return the products and quantities to reserve or release
     */
    private static List<StockLine> stockLines(List<OrderEvents.Line> items) {
        return items.stream()
                .map(line -> new StockLine(UUID.fromString(line.productId()), line.quantity()))
                .toList();
    }
}
