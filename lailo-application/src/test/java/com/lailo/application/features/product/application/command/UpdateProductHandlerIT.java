package com.lailo.application.features.product.application.command;

import com.lailo.application.common.web.ApiException;
import com.lailo.application.features.product.application.query.ProductView;
import com.lailo.application.features.product.domain.Product;
import com.lailo.application.features.product.infrastructure.persistence.ProductRepository;
import com.lailo.application.support.IntegrationTest;
import com.lailo.application.support.ProductFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Update rules on real PostgreSQL: full-state events, a stable slug and archived products refusing edits. */
class UpdateProductHandlerIT extends IntegrationTest {

    @Autowired CreateProductHandler createHandler;
    @Autowired UpdateProductHandler handler;
    @Autowired ArchiveProductHandler archiveHandler;
    @Autowired ProductRepository products;
    @Autowired DatabaseClient db;

    /**
     * Reads the event types the outbox holds for one aggregate, oldest first.
     *
     * @param productId aggregate id
     * @return the recorded event types
     */
    private List<String> outboxEvents(String productId) {
        return db.sql("SELECT event_type FROM outbox_messages WHERE aggregate_id = :id ORDER BY created_at")
                .bind("id", productId).map(r -> r.get(0, String.class)).all().collectList().block();
    }

    /**
     * An update rewrites the details but keeps the slug.
     * <ul>
     *   <li><b>Given</b> an existing product</li>
     *   <li><b>When</b> it is renamed and repriced</li>
     *   <li><b>Then</b> the stored row carries the new details, the slug is unchanged, {@code updatedAt} moved
     *       on and the outbox holds {@code ProductCreated} then {@code ProductUpdated}</li>
     * </ul>
     */
    @Test
    void appliesNewDetailsAndKeepsTheSlug() {
        String category = ProductFixtures.uniqueCategory();
        ProductView created = createHandler.handle(new CreateProductCommand(
                ProductFixtures.details("Yoga Mat", category))).block();

        ProductView updated = handler.handle(new UpdateProductCommand(UUID.fromString(created.id()),
                ProductFixtures.draft("Yoga Mat Pro", category)
                        .brand("Lailo Active")
                        .price(new BigDecimal("2499.00"))
                        .stockQuantity(3)
                        .build())).block();

        assertThat(updated.slug()).isEqualTo(created.slug());
        assertThat(updated.name()).isEqualTo("Yoga Mat Pro");
        assertThat(updated.brand()).isEqualTo("Lailo Active");
        assertThat(updated.price()).isEqualByComparingTo("2499.00");
        assertThat(updated.stockQuantity()).isEqualTo(3);
        assertThat(updated.updatedAt()).isAfterOrEqualTo(created.updatedAt());

        Product stored = products.findById(UUID.fromString(created.id())).block();
        assertThat(stored.getName()).isEqualTo("Yoga Mat Pro");
        assertThat(outboxEvents(created.id())).containsExactly("ProductCreated", "ProductUpdated");
    }

    /**
     * Updating an unknown product fails with a 404 code.
     * <ul>
     *   <li><b>Given</b> a random product id</li>
     *   <li><b>When</b> the update command is handled</li>
     *   <li><b>Then</b> it fails with {@code PRODUCT_NOT_FOUND}</li>
     * </ul>
     */
    @Test
    void unknownProductIsRejected() {
        StepVerifier.create(handler.handle(new UpdateProductCommand(UUID.randomUUID(),
                        ProductFixtures.details("Ghost", ProductFixtures.uniqueCategory()))))
                .expectErrorSatisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("PRODUCT_NOT_FOUND"))
                .verify();
    }

    /**
     * An archived product cannot be edited.
     * <ul>
     *   <li><b>Given</b> a product that has been archived</li>
     *   <li><b>When</b> an update is attempted</li>
     *   <li><b>Then</b> it fails with {@code PRODUCT_ARCHIVED} and no update event is recorded</li>
     * </ul>
     */
    @Test
    void archivedProductRefusesEdits() {
        String category = ProductFixtures.uniqueCategory();
        ProductView created = createHandler.handle(new CreateProductCommand(
                ProductFixtures.details("Clay Pot", category))).block();
        archiveHandler.handle(new ArchiveProductCommand(UUID.fromString(created.id()))).block();

        StepVerifier.create(handler.handle(new UpdateProductCommand(UUID.fromString(created.id()),
                        ProductFixtures.details("Clay Pot XL", category))))
                .expectErrorSatisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("PRODUCT_ARCHIVED"))
                .verify();

        assertThat(outboxEvents(created.id())).doesNotContain("ProductUpdated");
    }
}
