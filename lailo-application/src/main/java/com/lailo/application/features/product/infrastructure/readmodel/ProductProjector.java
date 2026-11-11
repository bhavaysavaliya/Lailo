package com.lailo.application.features.product.infrastructure.readmodel;

import com.lailo.application.features.product.domain.ProductStatus;
import com.lailo.application.features.product.domain.event.ProductEvents;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Instant;

/**
 * Projects product events into the catalogue. Delivery is at-least-once and partitioned per product, but a
 * retry can still replay an older event: every write is therefore conditional on
 * {@code updatedAt < occurredAt}, so a replayed or late event cannot resurrect stale data.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductProjector {

    private final ReactiveMongoTemplate mongo;

    /**
     * Projects {@code ProductCreated}: writes the whole document.
     *
     * @param e the event
     * @return completion signal
     */
    public Mono<Void> on(ProductEvents.ProductCreated e) {
        Update u = details(e.slug(), e.name(), e.description(), e.brand(), e.category(),
                e.price(), e.currency(), e.stockQuantity())
                .set("status", ProductStatus.ACTIVE.name())
                .set("createdAt", e.occurredAt());
        return apply(e.productId(), e.occurredAt(), u);
    }

    /**
     * Projects {@code ProductUpdated}: overwrites the details. The document is created if the
     * {@code ProductCreated} event has not been consumed yet.
     *
     * @param e the event
     * @return completion signal
     */
    public Mono<Void> on(ProductEvents.ProductUpdated e) {
        Update u = details(e.slug(), e.name(), e.description(), e.brand(), e.category(),
                e.price(), e.currency(), e.stockQuantity())
                .setOnInsert("status", ProductStatus.ACTIVE.name())
                .setOnInsert("createdAt", e.occurredAt());
        return apply(e.productId(), e.occurredAt(), u);
    }

    /**
     * Projects {@code ProductArchived}: the entry leaves the catalogue but is kept for order history.
     *
     * @param e the event
     * @return completion signal
     */
    public Mono<Void> on(ProductEvents.ProductArchived e) {
        Update u = new Update()
                .set("status", ProductStatus.ARCHIVED.name())
                .setOnInsert("createdAt", e.occurredAt());
        return apply(e.productId(), e.occurredAt(), u);
    }

    /**
     * Projects {@code ProductStockChanged}: only the quantity moves, so nothing else in the document is
     * touched and a concurrent price update cannot be overwritten by a stale stock event.
     *
     * @param e the event
     * @return completion signal
     */
    public Mono<Void> on(ProductEvents.ProductStockChanged e) {
        Update u = new Update()
                .set("stockQuantity", e.stockQuantity())
                .setOnInsert("createdAt", e.occurredAt());
        return apply(e.productId(), e.occurredAt(), u);
    }

    /**
     * Projects {@code ProductMediaAdded}: writes the item under its id, which makes a redelivery a no-op
     * rewrite instead of a duplicate gallery entry.
     *
     * @param e the event
     * @return completion signal
     */
    public Mono<Void> on(ProductEvents.ProductMediaAdded e) {
        Update u = new Update()
                .set("media." + e.mediaId(), ProductMediaDocument.builder()
                        .id(e.mediaId())
                        .type(e.type())
                        .objectKey(e.objectKey())
                        .contentType(e.contentType())
                        .sizeBytes(e.sizeBytes())
                        .position(e.position())
                        .build())
                .setOnInsert("createdAt", e.occurredAt());
        return apply(e.productId(), e.occurredAt(), u);
    }

    /**
     * Projects {@code ProductMediaRemoved}: drops the item from the gallery.
     *
     * @param e the event
     * @return completion signal
     */
    public Mono<Void> on(ProductEvents.ProductMediaRemoved e) {
        Update u = new Update()
                .unset("media." + e.mediaId())
                .setOnInsert("createdAt", e.occurredAt());
        return apply(e.productId(), e.occurredAt(), u);
    }

    /**
     * Builds the {@code $set} operations shared by the create and update events. Nulls are set too: clearing a
     * description must clear it in the catalogue as well.
     *
     * @param slug URL segment
     * @param name display name
     * @param description long description, or {@code null}
     * @param brand brand name, or {@code null}
     * @param category catalogue category
     * @param price current price
     * @param currency ISO 4217 code
     * @param stockQuantity units available
     * @return the partially built update
     */
    private static Update details(String slug, String name, String description, String brand, String category,
                                  java.math.BigDecimal price, String currency, int stockQuantity) {
        return new Update()
                .set("slug", slug)
                .set("name", name)
                .set("description", description)
                .set("brand", brand)
                .set("category", category)
                .set("price", price)
                .set("currency", currency)
                .set("stockQuantity", stockQuantity);
    }

    /**
     * Upserts the document, but only when the stored copy is older than this event.
     *
     * <p>With an upsert, a document that exists yet fails the {@code updatedAt} guard makes MongoDB attempt an
     * insert, which the {@code _id} index rejects. That duplicate-key error is exactly the "stale event"
     * case, so it is swallowed.</p>
     *
     * @param productId document id (same as the product id)
     * @param occurredAt event time, stored as {@code updatedAt}
     * @param update the update to apply
     * @return completion signal
     */
    private Mono<Void> apply(String productId, Instant occurredAt, Update update) {
        Criteria criteria = Criteria.where("_id").is(productId)
                .orOperator(Criteria.where("updatedAt").lt(occurredAt),
                        Criteria.where("updatedAt").exists(false));
        return mongo.findAndModify(Query.query(criteria), update.set("updatedAt", occurredAt),
                        FindAndModifyOptions.options().upsert(true), ProductDocument.class)
                .then()
                .onErrorResume(DuplicateKeyException.class, ex -> {
                    log.debug("Skipped stale product event for {} (newer state already projected)", productId);
                    return Mono.empty();
                });
    }
}
