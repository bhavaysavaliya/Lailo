package com.lailo.application.features.product.application.command;

import com.lailo.application.common.web.ApiException;
import com.lailo.application.features.product.application.query.ProductView;
import com.lailo.application.features.product.domain.ProductStatus;
import com.lailo.application.features.product.infrastructure.persistence.ProductRepository;
import com.lailo.application.support.IntegrationTest;
import com.lailo.application.support.ProductFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Soft delete on real PostgreSQL: the row survives, the catalogue entry does not, and repeats are no-ops. */
class ArchiveProductHandlerIT extends IntegrationTest {

    @Autowired CreateProductHandler createHandler;
    @Autowired ArchiveProductHandler handler;
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
     * Archiving keeps the row, flips the status and is idempotent.
     * <ul>
     *   <li><b>Given</b> an active product</li>
     *   <li><b>When</b> it is archived twice</li>
     *   <li><b>Then</b> the first call reports {@code true} and the second {@code false}, the row is still
     *       present with status {@code ARCHIVED}, and exactly one {@code ProductArchived} event was recorded</li>
     * </ul>
     */
    @Test
    void archivesOnceAndIsIdempotent() {
        ProductView created = createHandler.handle(new CreateProductCommand(
                ProductFixtures.details("Brass Diya", ProductFixtures.uniqueCategory()))).block();
        UUID id = UUID.fromString(created.id());

        assertThat(handler.handle(new ArchiveProductCommand(id)).block()).isTrue();
        assertThat(handler.handle(new ArchiveProductCommand(id)).block()).isFalse();

        assertThat(products.findById(id).block().getStatus()).isEqualTo(ProductStatus.ARCHIVED);
        assertThat(outboxEvents(created.id())).containsExactly("ProductCreated", "ProductArchived");
    }

    /**
     * Archiving an unknown product fails with a 404 code.
     * <ul>
     *   <li><b>Given</b> a random product id</li>
     *   <li><b>When</b> the archive command is handled</li>
     *   <li><b>Then</b> it fails with {@code PRODUCT_NOT_FOUND}</li>
     * </ul>
     */
    @Test
    void unknownProductIsRejected() {
        StepVerifier.create(handler.handle(new ArchiveProductCommand(UUID.randomUUID())))
                .expectErrorSatisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("PRODUCT_NOT_FOUND"))
                .verify();
    }
}
