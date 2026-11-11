package com.lailo.application.features.wishlist.application.command;

import com.lailo.application.common.cqrs.CommandHandler;
import com.lailo.application.common.outbox.OutboxPublisher;
import com.lailo.application.features.wishlist.application.WishlistErrors;
import com.lailo.application.features.wishlist.application.query.WishlistReader;
import com.lailo.application.features.wishlist.application.query.WishlistView;
import com.lailo.application.features.wishlist.domain.event.WishlistEvents;
import com.lailo.application.features.wishlist.infrastructure.persistence.WishlistItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/** Unsaves a product and records why, which is what the "back in stock" consumers key off. */
@Component
@RequiredArgsConstructor
public class RemoveFromWishlistHandler implements CommandHandler<RemoveFromWishlistCommand, WishlistView> {

    private final WishlistItemRepository wishlistItems;
    private final WishlistReader reader;
    private final OutboxPublisher outbox;
    private final TransactionalOperator tx;
    private final Clock clock;

    /**
     * Deletes the entry and appends its event in one transaction.
     *
     * @param cmd the request
     * @return the whole updated wishlist, or {@code WISHLIST_ITEM_NOT_FOUND}
     */
    @Override
    public Mono<WishlistView> handle(RemoveFromWishlistCommand cmd) {
        Instant now = Instant.now(clock);
        return Mono.defer(() -> wishlistItems.deleteByUserIdAndProductId(cmd.userId(), cmd.productId())
                .flatMap(deleted -> deleted == 0
                        ? Mono.<Void>error(WishlistErrors.itemNotFound(cmd.productId()))
                        : outbox.append(WishlistEvents.TOPIC, WishlistEvents.AGGREGATE,
                        cmd.userId().toString(), WishlistEvents.WISHLIST_ITEM_REMOVED,
                        new WishlistEvents.WishlistItemRemoved(UUID.randomUUID().toString(),
                                cmd.userId().toString(), cmd.productId().toString(),
                                cmd.reason().name(), now)).then())
                .as(tx::transactional)
                .then(reader.read(cmd.userId())));
    }

    /**
     * Serves {@link RemoveFromWishlistCommand}.
     *
     * @return the command class
     */
    @Override
    public Class<RemoveFromWishlistCommand> commandType() {
        return RemoveFromWishlistCommand.class;
    }
}
