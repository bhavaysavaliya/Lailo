package com.lailo.application.features.product.application.command;

import com.lailo.application.features.product.application.query.ProductView;
import com.lailo.application.features.product.domain.Product;
import com.lailo.application.features.product.domain.ProductStatus;
import com.lailo.application.features.product.infrastructure.persistence.ProductRepository;
import com.lailo.application.support.IntegrationTest;
import com.lailo.application.support.ProductFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.r2dbc.core.DatabaseClient;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Write side against real PostgreSQL: the product row and its outbox event commit together. */
class CreateProductHandlerIT extends IntegrationTest {

    @Autowired CreateProductHandler handler;
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
     * Creating a product stores it as active and records exactly one event.
     * <ul>
     *   <li><b>Given</b> details with an unrounded price</li>
     *   <li><b>When</b> the create command is handled</li>
     *   <li><b>Then</b> the returned view is the stored state, the row is {@code ACTIVE} in INR with the price
     *       scaled to two decimals, the slug ends with the id prefix, and one {@code ProductCreated} event sits
     *       in the outbox</li>
     * </ul>
     */
    @Test
    void storesTheProductAndAppendsOneEvent() {
        String category = ProductFixtures.uniqueCategory();

        ProductView view = handler.handle(new CreateProductCommand(ProductFixtures
                .draft("Cotton Kurta", category)
                .price(new BigDecimal("1999.5"))
                .stockQuantity(7)
                .build())).block();

        assertThat(view.status()).isEqualTo("ACTIVE");
        assertThat(view.currency()).isEqualTo("INR");
        assertThat(view.price()).isEqualByComparingTo("1999.50");
        assertThat(view.slug()).startsWith("cotton-kurta-")
                .endsWith(view.id().replace("-", "").substring(0, 8));

        Product stored = products.findById(UUID.fromString(view.id())).block();
        assertThat(stored.getStatus()).isEqualTo(ProductStatus.ACTIVE);
        assertThat(stored.getCategory()).isEqualTo(category);
        assertThat(stored.getStockQuantity()).isEqualTo(7);
        assertThat(stored.getPrice()).isEqualByComparingTo(new BigDecimal("1999.50"));
        assertThat(stored.getVersion()).isNotNull();

        assertThat(outboxEvents(view.id())).containsExactly("ProductCreated");
    }

    /**
     * Two products may share a name.
     * <ul>
     *   <li><b>Given</b> the same name created twice</li>
     *   <li><b>When</b> both create commands are handled</li>
     *   <li><b>Then</b> both are stored with different ids and different slugs</li>
     * </ul>
     */
    @Test
    void allowsDuplicateNamesWithDistinctSlugs() {
        String category = ProductFixtures.uniqueCategory();

        ProductView first = handler.handle(new CreateProductCommand(
                ProductFixtures.details("Steel Bottle", category))).block();
        ProductView second = handler.handle(new CreateProductCommand(
                ProductFixtures.details("Steel Bottle", category))).block();

        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(second.slug()).isNotEqualTo(first.slug());
        assertThat(products.findBySlug(first.slug()).block()).isNotNull();
        assertThat(products.findBySlug(second.slug()).block()).isNotNull();
    }
}
