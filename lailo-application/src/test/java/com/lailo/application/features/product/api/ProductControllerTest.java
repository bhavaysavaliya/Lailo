package com.lailo.application.features.product.api;

import com.lailo.application.common.cqrs.CommandBus;
import com.lailo.application.common.cqrs.QueryBus;
import com.lailo.application.features.auth.config.AuthBeansConfig;
import com.lailo.application.features.product.application.ProductErrors;
import com.lailo.application.features.product.application.command.ArchiveProductCommand;
import com.lailo.application.features.product.application.command.CreateProductCommand;
import com.lailo.application.features.product.application.command.UpdateProductCommand;
import com.lailo.application.features.product.application.query.GetProductQuery;
import com.lailo.application.features.product.application.query.ProductMediaView;
import com.lailo.application.features.product.application.query.ProductPage;
import com.lailo.application.features.product.application.query.ProductSort;
import com.lailo.application.features.product.application.query.ProductView;
import com.lailo.application.features.product.application.query.SearchProductsQuery;
import com.lailo.application.security.auth.config.AuthConfig;
import com.lailo.application.support.TestAuthProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.csrf;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

/** HTTP contract of the catalogue API: parameter binding, validation, auth, CSRF and error format. */
@WebFluxTest(controllers = ProductController.class)
@Import({AuthConfig.class, AuthBeansConfig.class})
@TestPropertySource(properties = {
        "lailo.auth.otp.hash-secret=" + TestAuthProperties.OTP_SECRET,
        "lailo.auth.jwt.secret=" + TestAuthProperties.JWT_SECRET
})
class ProductControllerTest {

    private static final UUID PRODUCT_ID = UUID.fromString("11111111-2222-4333-8444-555555555555");

    @Autowired WebTestClient client;
    @MockitoBean CommandBus commandBus;
    @MockitoBean QueryBus queryBus;

    /**
     * Builds a product as the handlers would return it.
     *
     * @return a view with a fixed id and price
     */
    private static ProductView view() {
        Instant now = Instant.parse("2026-11-08T10:00:00Z");
        return ProductView.builder()
                .id(PRODUCT_ID.toString())
                .slug("cotton-kurta-11111111")
                .name("Cotton Kurta")
                .description("Soft cotton")
                .brand("Lailo Basics")
                .category("clothing")
                .price(new BigDecimal("1299.00"))
                .currency("INR")
                .stockQuantity(12)
                .status("ACTIVE")
                .createdAt(now)
                .updatedAt(now)
                .media(List.of(ProductMediaView.builder()
                        .id("33333333-4444-4555-8666-777777777777")
                        .type("IMAGE")
                        .url("http://localhost:8333/lailo-media/products/p/m.webp")
                        .contentType("image/webp")
                        .sizeBytes(4096)
                        .position(0)
                        .build()))
                .build();
    }

    /**
     * A valid create body.
     *
     * @return the request body
     */
    private static Map<String, Object> body() {
        return Map.of("name", "Cotton Kurta", "description", "Soft cotton", "brand", "Lailo Basics",
                "category", "clothing", "price", "1299.00", "stockQuantity", 12);
    }

    /**
     * Browsing is public and its parameters reach the query unchanged.
     * <ul>
     *   <li><b>Given</b> the query bus returns one page</li>
     *   <li><b>When</b> an anonymous {@code GET /api/v1/products} is made with every filter set</li>
     *   <li><b>Then</b> status is 200, the response is publicly cacheable for 60s and the dispatched query
     *       carries the text, category, price range, stock flag, sort and paging</li>
     * </ul>
     */
    @Test
    void searchIsPublicAndBindsEveryFilter() {
        when(queryBus.ask(any(SearchProductsQuery.class)))
                .thenReturn(Mono.just(ProductPage.builder()
                        .items(List.of(view())).page(1).size(5).total(11).hasNext(true).build()));

        client.get().uri("/api/v1/products?q=kurta&category=clothing&minPrice=500&maxPrice=2000"
                        + "&inStock=true&sort=PRICE_ASC&page=1&size=5").exchange()
                .expectStatus().isOk()
                .expectHeader().cacheControl(org.springframework.http.CacheControl
                        .maxAge(java.time.Duration.ofSeconds(60)).cachePublic())
                .expectBody()
                .jsonPath("$.total").isEqualTo(11)
                .jsonPath("$.hasNext").isEqualTo(true)
                .jsonPath("$.items[0].name").isEqualTo("Cotton Kurta");

        ArgumentCaptor<SearchProductsQuery> captor = ArgumentCaptor.forClass(SearchProductsQuery.class);
        verify(queryBus).ask(captor.capture());
        SearchProductsQuery query = captor.getValue();
        assertThat(query.text()).isEqualTo("kurta");
        assertThat(query.category()).isEqualTo("clothing");
        assertThat(query.minPrice()).isEqualByComparingTo("500");
        assertThat(query.maxPrice()).isEqualByComparingTo("2000");
        assertThat(query.inStockOnly()).isTrue();
        assertThat(query.sort()).isEqualTo(ProductSort.PRICE_ASC);
        assertThat(query.page()).isEqualTo(1);
        assertThat(query.size()).isEqualTo(5);
    }

    /**
     * Browsing without parameters uses the defaults.
     * <ul>
     *   <li><b>Given</b> the query bus returns an empty page</li>
     *   <li><b>When</b> {@code GET /api/v1/products} is called with no parameters</li>
     *   <li><b>Then</b> the query has no filters, {@code NEWEST} order and page 0 of the default size</li>
     * </ul>
     */
    @Test
    void searchDefaultsToNewestFirstPage() {
        when(queryBus.ask(any(SearchProductsQuery.class)))
                .thenReturn(Mono.just(ProductPage.builder()
                        .items(List.of()).page(0).size(20).total(0).hasNext(false).build()));

        client.get().uri("/api/v1/products").exchange().expectStatus().isOk();

        ArgumentCaptor<SearchProductsQuery> captor = ArgumentCaptor.forClass(SearchProductsQuery.class);
        verify(queryBus).ask(captor.capture());
        assertThat(captor.getValue().text()).isNull();
        assertThat(captor.getValue().category()).isNull();
        assertThat(captor.getValue().inStockOnly()).isFalse();
        assertThat(captor.getValue().sort()).isEqualTo(ProductSort.NEWEST);
        assertThat(captor.getValue().page()).isZero();
        assertThat(captor.getValue().size()).isEqualTo(SearchProductsQuery.DEFAULT_SIZE);
    }

    /**
     * An unknown product becomes an RFC 9457 problem response.
     * <ul>
     *   <li><b>Given</b> the query bus fails with {@code PRODUCT_NOT_FOUND}</li>
     *   <li><b>When</b> the product is requested</li>
     *   <li><b>Then</b> status is 404 and the body carries the code and the product id</li>
     * </ul>
     */
    @Test
    void unknownProductIsReportedAsProblemDetail() {
        when(queryBus.ask(new GetProductQuery(PRODUCT_ID))).thenReturn(Mono.error(ProductErrors.notFound(PRODUCT_ID)));

        client.get().uri("/api/v1/products/" + PRODUCT_ID).exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.code").isEqualTo("PRODUCT_NOT_FOUND")
                .jsonPath("$.productId").isEqualTo(PRODUCT_ID.toString());
    }

    /**
     * A path variable that is not a UUID is a bad request, not a server error.
     * <ul>
     *   <li><b>Given</b> the path {@code /api/v1/products/not-a-uuid}</li>
     *   <li><b>When</b> it is requested</li>
     *   <li><b>Then</b> status is 400 with {@code BAD_REQUEST} and no query is dispatched</li>
     * </ul>
     */
    @Test
    void malformedProductIdIsABadRequest() {
        client.get().uri("/api/v1/products/not-a-uuid").exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("BAD_REQUEST");

        verifyNoInteractions(queryBus);
    }

    /**
     * Creating a product requires a signed-in caller and a CSRF token.
     * <ul>
     *   <li><b>Given</b> a valid body</li>
     *   <li><b>When</b> it is posted anonymously with a CSRF token, and then without one</li>
     *   <li><b>Then</b> the calls get 401 and 403, and no command is dispatched</li>
     * </ul>
     */
    @Test
    void createRejectsAnonymousAndCsrfLessCalls() {
        client.mutateWith(csrf()).post().uri("/api/v1/products")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body())
                .exchange().expectStatus().isUnauthorized();

        client.post().uri("/api/v1/products").contentType(MediaType.APPLICATION_JSON).bodyValue(body())
                .exchange().expectStatus().isForbidden();

        verifyNoInteractions(commandBus);
    }

    /**
     * A signed-in caller creates a product.
     * <ul>
     *   <li><b>Given</b> the command bus returns the created product</li>
     *   <li><b>When</b> an authenticated {@code POST /api/v1/products} is made</li>
     *   <li><b>Then</b> status is 201, {@code Location} points at the new product and the command carries the
     *       normalised details: name trimmed, blank description dropped and price scaled to two decimals</li>
     * </ul>
     */
    @Test
    void createReturnsCreatedWithLocation() {
        when(commandBus.dispatch(any(CreateProductCommand.class))).thenReturn(Mono.just(view()));

        client.mutateWith(mockJwt()).mutateWith(csrf()).post().uri("/api/v1/products")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "  Cotton Kurta  ", "description", " ", "brand", "Lailo Basics",
                        "category", "clothing", "price", "1299.5", "stockQuantity", 12))
                .exchange()
                .expectStatus().isCreated()
                .expectHeader().location("/api/v1/products/" + PRODUCT_ID)
                .expectBody()
                .jsonPath("$.slug").isEqualTo("cotton-kurta-11111111")
                .jsonPath("$.media[0].url").isEqualTo("http://localhost:8333/lailo-media/products/p/m.webp");

        ArgumentCaptor<CreateProductCommand> captor = ArgumentCaptor.forClass(CreateProductCommand.class);
        verify(commandBus).dispatch(captor.capture());
        assertThat(captor.getValue().details().name()).isEqualTo("Cotton Kurta");
        assertThat(captor.getValue().details().description()).isNull();
        assertThat(captor.getValue().details().price()).isEqualTo(new BigDecimal("1299.50"));
    }

    /**
     * Invalid bodies are rejected before any command runs.
     * <ul>
     *   <li><b>Given</b> a body with a blank name, no category and a zero price</li>
     *   <li><b>When</b> it is posted by a signed-in caller</li>
     *   <li><b>Then</b> status is 400 with {@code VALIDATION_FAILED}, the field errors name every bad field and
     *       the command bus is untouched</li>
     * </ul>
     */
    @Test
    void invalidBodyIsRejectedWithFieldErrors() {
        client.mutateWith(mockJwt()).mutateWith(csrf()).post().uri("/api/v1/products")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "  ", "price", "0", "stockQuantity", -1))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("VALIDATION_FAILED")
                .jsonPath("$.errors.name").exists()
                .jsonPath("$.errors.category").exists()
                .jsonPath("$.errors.price").exists()
                .jsonPath("$.errors.stockQuantity").exists();

        verifyNoInteractions(commandBus);
    }

    /**
     * Updating dispatches the update command for the product in the path.
     * <ul>
     *   <li><b>Given</b> the command bus returns the updated product</li>
     *   <li><b>When</b> an authenticated {@code PUT} is made</li>
     *   <li><b>Then</b> status is 200 and the command carries the path id and the new details</li>
     * </ul>
     */
    @Test
    void updateDispatchesTheCommandForThePathId() {
        when(commandBus.dispatch(any(UpdateProductCommand.class))).thenReturn(Mono.just(view()));

        client.mutateWith(mockJwt()).mutateWith(csrf()).put().uri("/api/v1/products/" + PRODUCT_ID)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body())
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.id").isEqualTo(PRODUCT_ID.toString());

        ArgumentCaptor<UpdateProductCommand> captor = ArgumentCaptor.forClass(UpdateProductCommand.class);
        verify(commandBus).dispatch(captor.capture());
        assertThat(captor.getValue().productId()).isEqualTo(PRODUCT_ID);
        assertThat(captor.getValue().details().category()).isEqualTo("clothing");
    }

    /**
     * Deleting archives the product and answers 204.
     * <ul>
     *   <li><b>Given</b> the command bus reports the product was archived</li>
     *   <li><b>When</b> an authenticated {@code DELETE} is made</li>
     *   <li><b>Then</b> status is 204 with no body and the archive command was dispatched</li>
     * </ul>
     */
    @Test
    void deleteArchivesTheProduct() {
        when(commandBus.dispatch(new ArchiveProductCommand(PRODUCT_ID))).thenReturn(Mono.just(true));

        client.mutateWith(mockJwt()).mutateWith(csrf()).delete().uri("/api/v1/products/" + PRODUCT_ID)
                .exchange()
                .expectStatus().isNoContent()
                .expectBody().isEmpty();

        verify(commandBus).dispatch(new ArchiveProductCommand(PRODUCT_ID));
    }
}
