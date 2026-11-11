package com.lailo.application.features.wishlist.application.command;

import com.lailo.application.common.cqrs.CommandHandler;
import com.lailo.application.common.cqrs.QueryBus;
import com.lailo.application.common.outbox.OutboxPublisher;
import com.lailo.application.features.product.application.ProductErrors;
import com.lailo.application.features.product.application.query.GetProductsQuery;
import com.lailo.application.features.wishlist.application.query.WishlistReader;
import com.lailo.application.features.wishlist.application.query.WishlistView;
import com.lailo.application.features.wishlist.domain.WishlistItem;
import com.lailo.application.features.wishlist.domain.event.WishlistEvents;
import com.lailo.application.features.wishlist.infrastructure.persistence.WishlistItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Saves a product for later.
 *
 * <p>Unlike the cart, an archived or out-of-stock product may be saved: "tell me when this is back" is a
 * legitimate thing to want, and the view marks such entries unavailable. Only a product that never existed
 * is refused.</p>
 */
@Component
@RequiredArgsConstructor
public class AddToWishlistHandler implements CommandHandler<AddToWishlistCommand, WishlistView> {

    private final WishlistItemRepository wishlistItems;
    private final WishlistReader reader;
    private final QueryBus queryBus;
    private final OutboxPublisher outbox;
    private final TransactionalOperator tx;
    private final Clock clock;

    /**
     * Saves the product, or updates the note when it was already saved.
     *
     * @param cmd the request
     * @return the whole updated wishlist, or {@code PRODUCT_NOT_FOUND}
     */
    @Override
    public Mono<WishlistView> handle(AddToWishlistCommand cmd) {
        Instant now = Instant.now(clock);
        String note = trimNote(cmd.note());
        return Mono.defer(() -> queryBus.ask(new GetProductsQuery(Set.of(cmd.productId())))
                .flatMap(found -> found.isEmpty()
                        ? Mono.<Void>error(ProductErrors.notFound(cmd.productId()))
                        : save(cmd, note, now))
                .as(tx::transactional)
                .then(reader.read(cmd.userId())));
    }

    /**
     * Inserts the entry, or rewrites the note of the existing one.
     *
     * @param cmd the request
     * @param note normalised note
     * @param now change time
     * @return completion signal
     */
    private Mono<Void> save(AddToWishlistCommand cmd, String note, Instant now) {
        // The lookup is mapped into an Optional rather than chained with switchIfEmpty: the update branch
        // ends in Mono<Void>, which completes empty, and switchIfEmpty would then run the insert branch too
        // and hit the unique index.
        return wishlistItems.findByUserIdAndProductId(cmd.userId(), cmd.productId())
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(existing -> existing
                        .map(item -> wishlistItems.save(item.withNote(note)).then())
                        .orElseGet(() -> insert(cmd, note, now)));
    }

    /**
     * Stores a new entry and announces it.
     *
     * @param cmd the request
     * @param note normalised note
     * @param now change time
     * @return completion signal
     */
    private Mono<Void> insert(AddToWishlistCommand cmd, String note, Instant now) {
        return wishlistItems.save(WishlistItem.create(cmd.userId(), cmd.productId(), note, now))
                .flatMap(saved -> outbox.append(WishlistEvents.TOPIC, WishlistEvents.AGGREGATE,
                        saved.getUserId().toString(), WishlistEvents.WISHLIST_ITEM_ADDED,
                        new WishlistEvents.WishlistItemAdded(UUID.randomUUID().toString(),
                                saved.getUserId().toString(), saved.getProductId().toString(), now)))
                .then();
    }

    /**
     * Trims a note and drops it when blank, so an empty box never stores an empty string.
     *
     * @param note raw note from the request
     * @return the normalised note, or {@code null}
     */
    private static String trimNote(String note) {
        if (note == null || note.isBlank()) {
            return null;
        }
        String trimmed = note.trim();
        return trimmed.length() > WishlistItem.MAX_NOTE_LENGTH
                ? trimmed.substring(0, WishlistItem.MAX_NOTE_LENGTH)
                : trimmed;
    }

    /**
     * Serves {@link AddToWishlistCommand}.
     *
     * @return the command class
     */
    @Override
    public Class<AddToWishlistCommand> commandType() {
        return AddToWishlistCommand.class;
    }
}
