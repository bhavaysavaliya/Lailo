package com.lailo.application.features.product.application.query;

import com.lailo.application.common.web.ApiException;
import com.lailo.application.features.product.domain.Product;
import com.lailo.application.features.product.domain.ProductDetails;
import com.lailo.application.features.product.infrastructure.persistence.ProductRepository;
import com.lailo.application.features.product.infrastructure.readmodel.ProductCatalogRepository;
import com.lailo.application.features.product.infrastructure.readmodel.ProductDocument;
import com.lailo.application.support.IntegrationTest;
import com.lailo.application.support.ProductFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Single-product reads against real MongoDB (read model) with PostgreSQL fallback. */
class GetProductHandlerIT extends IntegrationTest {

    private static final Instant T = Instant.parse("2026-11-08T10:00:00Z");

    @Autowired GetProductHandler handler;
    @Autowired ProductCatalogRepository catalog;
    @Autowired ProductRepository products;

    /**
     * The catalogue document is preferred over the write model.
     * <ul>
     *   <li><b>Given</b> a product document in MongoDB</li>
     *   <li><b>When</b> the product is queried by id</li>
     *   <li><b>Then</b> the view matches the document, price included</li>
     * </ul>
     */
    @Test
    void servesTheMongoProjection() {
        String id = UUID.randomUUID().toString();
        catalog.save(ProductDocument.builder().id(id).slug("tea-set-" + id.substring(0, 8)).name("Tea Set")
                .brand("Lailo Home").category(ProductFixtures.uniqueCategory()).price(new BigDecimal("1499.00"))
                .currency("INR").stockQuantity(4).status("ACTIVE").createdAt(T).updatedAt(T).build()).block();

        StepVerifier.create(handler.handle(new GetProductQuery(UUID.fromString(id))))
                .assertNext(view -> {
                    assertThat(view.id()).isEqualTo(id);
                    assertThat(view.name()).isEqualTo("Tea Set");
                    assertThat(view.price()).isEqualByComparingTo("1499.00");
                    assertThat(view.stockQuantity()).isEqualTo(4);
                    assertThat(view.createdAt()).isEqualTo(T);
                })
                .verifyComplete();
    }

    /**
     * A product created a moment ago is readable before Kafka has projected it.
     * <ul>
     *   <li><b>Given</b> a product in Postgres with no MongoDB document</li>
     *   <li><b>When</b> the product is queried by id</li>
     *   <li><b>Then</b> the view is built from the Postgres row</li>
     * </ul>
     */
    @Test
    void fallsBackToPostgresWhileTheProjectionCatchesUp() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS); // Postgres timestamp precision
        ProductDetails details = ProductFixtures.details("Copper Jug", ProductFixtures.uniqueCategory());
        Product saved = products.save(Product.create(UUID.randomUUID(), details, now)).block();

        StepVerifier.create(handler.handle(new GetProductQuery(saved.getId())))
                .assertNext(view -> {
                    assertThat(view.id()).isEqualTo(saved.getId().toString());
                    assertThat(view.slug()).isEqualTo(saved.getSlug());
                    assertThat(view.price()).isEqualByComparingTo(details.price());
                    assertThat(view.createdAt()).isEqualTo(now);
                })
                .verifyComplete();
    }

    /**
     * An unknown id is a 404.
     * <ul>
     *   <li><b>Given</b> a random product id present in neither store</li>
     *   <li><b>When</b> the product is queried</li>
     *   <li><b>Then</b> it fails with {@code PRODUCT_NOT_FOUND}</li>
     * </ul>
     */
    @Test
    void unknownProductIsNotFound() {
        StepVerifier.create(handler.handle(new GetProductQuery(UUID.randomUUID())))
                .expectErrorSatisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("PRODUCT_NOT_FOUND"))
                .verify();
    }
}
