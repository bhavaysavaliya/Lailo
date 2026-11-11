package com.lailo.application.features.cart.application.command;

import com.lailo.application.common.cqrs.CommandHandler;
import com.lailo.application.common.outbox.OutboxPublisher;
import com.lailo.application.features.cart.domain.event.CartEvents;
import com.lailo.application.features.cart.infrastructure.persistence.CartItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Empties a cart. Idempotent: clearing an empty cart succeeds and emits nothing.
 *
 * <p>No {@code TransactionalOperator} boundary is forced when this runs inside checkout - the reactive
 * transaction is carried in the subscriber context, so dispatching this command from
 * {@code PlaceOrderHandler} joins that transaction and the cart disappears exactly when the order appears.
 * </p>
 */
@Component
@RequiredArgsConstructor
public class ClearCartHandler implements CommandHandler<ClearCartCommand, Boolean> {

    private final CartItemRepository cartItems;
    private final OutboxPublisher outbox;
    private final TransactionalOperator tx;
    private final Clock clock;

    /**
     * Deletes every line and appends {@code CartCleared} when there was something to clear.
     *
     * @param cmd the request
     * @return {@code true} when lines were deleted, {@code false} when the cart was already empty
     */
    @Override
    public Mono<Boolean> handle(ClearCartCommand cmd) {
        Instant now = Instant.now(clock);
        return Mono.defer(() -> cartItems.deleteByUserId(cmd.userId())
                .flatMap(deleted -> deleted == 0
                        ? Mono.just(false)
                        : outbox.append(CartEvents.TOPIC, CartEvents.AGGREGATE, cmd.userId().toString(),
                        CartEvents.CART_CLEARED,
                        new CartEvents.CartCleared(UUID.randomUUID().toString(), cmd.userId().toString(),
                                cmd.reason().name(), now)).thenReturn(true))
                .as(tx::transactional));
    }

    /**
     * Serves {@link ClearCartCommand}.
     *
     * @return the command class
     */
    @Override
    public Class<ClearCartCommand> commandType() {
        return ClearCartCommand.class;
    }
}
