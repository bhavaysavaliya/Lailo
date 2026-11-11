package com.lailo.application.features.order.application.command;

import com.lailo.application.common.cqrs.CommandBus;
import com.lailo.application.common.cqrs.CommandHandler;
import com.lailo.application.common.cqrs.QueryBus;
import com.lailo.application.common.outbox.OutboxPublisher;
import com.lailo.application.features.cart.application.CartErrors;
import com.lailo.application.features.cart.application.command.ClearCartCommand;
import com.lailo.application.features.cart.application.query.CartItemView;
import com.lailo.application.features.cart.application.query.CartView;
import com.lailo.application.features.cart.application.query.GetCartQuery;
import com.lailo.application.features.cart.domain.ClearCartReason;
import com.lailo.application.features.order.application.OrderErrors;
import com.lailo.application.features.order.application.query.OrderView;
import com.lailo.application.features.order.application.query.OrderViewMapper;
import com.lailo.application.features.order.config.CheckoutProperties;
import com.lailo.application.features.order.domain.Order;
import com.lailo.application.features.order.domain.OrderItem;
import com.lailo.application.features.order.domain.OrderNumber;
import com.lailo.application.features.order.domain.OrderTotals;
import com.lailo.application.features.order.domain.event.OrderEvents;
import com.lailo.application.features.order.infrastructure.persistence.OrderItemRepository;
import com.lailo.application.features.order.infrastructure.persistence.OrderRepository;
import com.lailo.application.features.payment.domain.PaymentMethod;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Places an order: the one synchronous step of checkout.
 *
 * <p>What happens here is only what has to be atomic with the order existing - the order row, its
 * snapshotted lines, the cart being emptied, and the {@code OrderPlaced} event. Reserving stock and taking
 * money cannot join that transaction (one is another aggregate, the other is a third party), so they are
 * driven afterwards by the checkout saga, which reads that event.</p>
 *
 * <p>Prices come from the cart read, which reads them live from the catalogue, so the shopper is charged
 * today's price and never what a client sent.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlaceOrderHandler implements CommandHandler<PlaceOrderCommand, OrderView> {

    private final OrderRepository orders;
    private final OrderItemRepository orderItems;
    private final OrderViewMapper mapper;
    private final CheckoutProperties checkout;
    private final CommandBus commandBus;
    private final QueryBus queryBus;
    private final OutboxPublisher outbox;
    private final TransactionalOperator tx;
    private final SecureRandom random;
    private final Clock clock;

    /**
     * Validates the cart, prices the order and writes it.
     *
     * @param cmd the request
     * @return the placed order, or {@code CART_EMPTY} / {@code CART_HAS_UNAVAILABLE_ITEMS} /
     *         {@code COD_UNAVAILABLE}
     */
    @Override
    public Mono<OrderView> handle(PlaceOrderCommand cmd) {
        return Mono.defer(() -> {
            if (cmd.paymentMethod() == PaymentMethod.COD && !checkout.codEnabled()) {
                return Mono.<OrderView>error(OrderErrors.codUnavailable());
            }
            Mono<CartView> cart = queryBus.ask(new GetCartQuery(cmd.userId()));
            // Mono.defer: without it, write() is built at assembly time and prices an empty cart
            // before validate() ever gets the chance to reject it.
            return cart.flatMap(contents -> validate(contents)
                    .then(Mono.defer(() -> write(cmd, contents))));
        });
    }

    /**
     * Refuses a cart that cannot become an order.
     *
     * @param cart the shopper's cart
     * @return completion signal, or an error
     */
    private static Mono<Void> validate(CartView cart) {
        if (cart.isEmpty()) {
            return Mono.error(CartErrors.empty());
        }
        if (cart.hasUnavailableItems()) {
            return Mono.error(OrderErrors.cartHasUnavailableItems());
        }
        return Mono.empty();
    }

    /**
     * Writes the order, its lines, the cart clear and the event in one transaction, retrying once if the
     * generated reference happens to collide.
     *
     * @param cmd the request
     * @param cart the shopper's cart
     * @return the placed order
     */
    private Mono<OrderView> write(PlaceOrderCommand cmd, CartView cart) {
        return persist(cmd, cart)
                // The reference is random, so a collision is astronomically unlikely - but the unique index
                // is what guarantees uniqueness, and a second draw is cheaper than pretending it cannot
                // happen.
                .onErrorResume(DuplicateKeyException.class, ex -> {
                    log.warn("Order number collision, retrying with a new one");
                    return persist(cmd, cart);
                });
    }

    /**
     * Does the actual write.
     *
     * @param cmd the request
     * @param cart the shopper's cart
     * @return the placed order
     */
    private Mono<OrderView> persist(PlaceOrderCommand cmd, CartView cart) {
        Instant now = Instant.now(clock);
        BigDecimal itemsTotal = cart.itemsTotal();
        OrderTotals totals = OrderTotals.of(itemsTotal, checkout.shippingFeeFor(itemsTotal), cart.currency());
        UUID orderId = UUID.randomUUID();
        Order order = Order.place(orderId, OrderNumber.generate(now, random), cmd.userId(), totals,
                cmd.paymentMethod(), cmd.address(), now);
        List<OrderItem> lines = cart.items().stream()
                .map(item -> snapshot(orderId, item, cart.currency()))
                .toList();
        Mono<Boolean> clearCart = commandBus.dispatch(
                new ClearCartCommand(cmd.userId(), ClearCartReason.CHECKED_OUT));

        return orders.save(order)
                .flatMap(saved -> Flux.fromIterable(lines).concatMap(orderItems::save).then(Mono.just(saved)))
                .flatMap(saved -> clearCart.thenReturn(saved))
                .flatMap(saved -> outbox.append(OrderEvents.TOPIC, OrderEvents.AGGREGATE,
                                saved.getId().toString(), OrderEvents.ORDER_PLACED,
                                OrderEvents.OrderPlaced.of(saved, lines))
                        .thenReturn(saved))
                .as(tx::transactional)
                .doOnNext(saved -> log.info("Order {} placed by {} for {} {}", saved.getOrderNumber(),
                        saved.getUserId(), saved.getCurrency(), saved.getTotalAmount()))
                .map(saved -> mapper.fromAggregate(saved, lines, null));
    }

    /**
     * Freezes one cart line into an order line.
     *
     * @param orderId owning order
     * @param item the cart line, already priced from the live catalogue
     * @param currency ISO 4217 code
     * @return the snapshotted line
     */
    private static OrderItem snapshot(UUID orderId, CartItemView item, String currency) {
        return OrderItem.snapshot(orderId, UUID.fromString(item.productId()), item.name(), item.slug(),
                item.imageUrl(), item.quantity(), item.unitPrice(), currency);
    }

    /**
     * Serves {@link PlaceOrderCommand}.
     *
     * @return the command class
     */
    @Override
    public Class<PlaceOrderCommand> commandType() {
        return PlaceOrderCommand.class;
    }
}
