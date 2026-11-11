package com.lailo.application.features.cart.application.command;

import com.lailo.application.common.cqrs.CommandHandler;
import com.lailo.application.common.outbox.OutboxPublisher;
import com.lailo.application.features.cart.application.CartErrors;
import com.lailo.application.features.cart.application.query.CartReader;
import com.lailo.application.features.cart.application.query.CartView;
import com.lailo.application.features.cart.domain.event.CartEvents;
import com.lailo.application.features.cart.infrastructure.persistence.CartItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/** Removes one cart line. Not idempotent on purpose: removing nothing is a 404, which surfaces stale UI. */
@Component
@RequiredArgsConstructor
public class RemoveFromCartHandler implements CommandHandler<RemoveFromCartCommand, CartView> {

    private final CartItemRepository cartItems;
    private final CartReader reader;
    private final OutboxPublisher outbox;
    private final TransactionalOperator tx;
    private final Clock clock;

    /**
     * Deletes the line and appends its event in one transaction.
     *
     * @param cmd the request
     * @return the whole updated cart, or {@code CART_ITEM_NOT_FOUND}
     */
    @Override
    public Mono<CartView> handle(RemoveFromCartCommand cmd) {
        Instant now = Instant.now(clock);
        return Mono.defer(() -> cartItems.deleteByUserIdAndProductId(cmd.userId(), cmd.productId())
                .flatMap(deleted -> deleted == 0
                        ? Mono.<Void>error(CartErrors.itemNotFound(cmd.productId()))
                        : outbox.append(CartEvents.TOPIC, CartEvents.AGGREGATE, cmd.userId().toString(),
                        CartEvents.CART_ITEM_REMOVED,
                        new CartEvents.CartItemRemoved(UUID.randomUUID().toString(), cmd.userId().toString(),
                                cmd.productId().toString(), now)).then())
                .as(tx::transactional)
                .then(reader.read(cmd.userId())));
    }

    /**
     * Serves {@link RemoveFromCartCommand}.
     *
     * @return the command class
     */
    @Override
    public Class<RemoveFromCartCommand> commandType() {
        return RemoveFromCartCommand.class;
    }
}
