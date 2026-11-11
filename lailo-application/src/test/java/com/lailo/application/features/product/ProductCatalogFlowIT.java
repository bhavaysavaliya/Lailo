package com.lailo.application.features.product;

import com.lailo.application.features.auth.domain.Identifier;
import com.lailo.application.features.auth.domain.User;
import com.lailo.application.features.auth.infrastructure.token.JwtTokenService;
import com.lailo.application.features.product.application.command.RequestProductMediaUploadCommand;
import com.lailo.application.features.product.application.query.ProductView;
import com.lailo.application.features.product.infrastructure.readmodel.ProductCatalogRepository;
import com.lailo.application.features.product.infrastructure.readmodel.ProductDocument;
import com.lailo.application.support.IntegrationTest;
import com.lailo.application.support.ProductFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end catalogue lifecycle over HTTP against real Postgres, Kafka and MongoDB:
 * create -> read-your-write -> outbox to Kafka to MongoDB projection -> public search -> update -> archive.
 */
class ProductCatalogFlowIT extends IntegrationTest {

    @Autowired WebTestClient client;
    @Autowired JwtTokenService jwt;
    @Autowired ProductCatalogRepository catalog;

    private String xsrf;

    /**
     * Seeds the CSRF cookie the same way a browser does and remembers its value.
     *
     * @return the CSRF token
     */
    private String csrf() {
        if (xsrf == null) {
            xsrf = client.get().uri("/api/v1/auth/csrf").exchange().expectStatus().isNoContent()
                    .returnResult(Void.class).getResponseCookies().get("XSRF-TOKEN").getFirst().getValue();
        }
        return xsrf;
    }

    /**
     * Issues an access token for a throwaway signed-in customer.
     *
     * @return the bearer token value
     */
    private String accessToken() {
        User user = User.register(UUID.randomUUID(), Identifier.parse(uniqueEmail(), "91"), Instant.now());
        return jwt.issue(user, UUID.randomUUID()).value();
    }

    /**
     * Sends a state-changing request with a CSRF token, optionally authenticated.
     *
     * @param method HTTP method
     * @param uri request path
     * @param body JSON body, or {@code null} for none
     * @param token bearer token, or {@code null} to stay anonymous
     * @return the response spec for assertions
     */
    private WebTestClient.ResponseSpec send(HttpMethod method, String uri, Object body, String token) {
        WebTestClient.RequestBodySpec req = client.method(method).uri(uri)
                .header("X-XSRF-TOKEN", csrf())
                .cookie("XSRF-TOKEN", csrf());
        if (token != null) {
            req.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return body == null ? req.exchange() : req.contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange();
    }

    /**
     * Polls MongoDB until the Kafka projection satisfies a condition.
     *
     * @param productId product to wait for
     * @param until condition the document must satisfy
     * @return the projected document
     * @throws AssertionError if the condition is not met within 20 seconds
     */
    private ProductDocument awaitCatalogue(String productId, Predicate<ProductDocument> until) {
        Instant deadline = Instant.now().plusSeconds(20);
        while (Instant.now().isBefore(deadline)) {
            ProductDocument doc = catalog.findById(productId).block(Duration.ofSeconds(2));
            if (doc != null && until.test(doc)) {
                return doc;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
        throw new AssertionError("Catalogue entry for " + productId + " did not reach the expected state within 20s");
    }

    /**
     * The whole catalogue lifecycle works across HTTP, Postgres, Kafka and MongoDB.
     * <ul>
     *   <li><b>Given</b> a signed-in customer and a fresh category</li>
     *   <li><b>When</b> a product is created, read back immediately, searched once projected, repriced and
     *       finally archived</li>
     *   <li><b>Then</b> creation returns 201 with a {@code Location}; the product is readable without signing
     *       in before the projection lands; search finds it; the reprice reaches MongoDB; and after archiving
     *       it disappears from search while the direct read still works</li>
     * </ul>
     */
    @Test
    void fullCatalogueLifecycle() {
        String token = accessToken();
        String category = ProductFixtures.uniqueCategory();
        Map<String, Object> body = Map.of("name", "Handloom Cushion", "description", "Woven in Bhuj",
                "brand", "Lailo Home", "category", category, "price", "1299.00", "stockQuantity", 12);

        // 1. create (write side)
        EntityExchangeResult<ProductView> created = send(HttpMethod.POST, "/api/v1/products", body, token)
                .expectStatus().isCreated()
                .expectBody(ProductView.class).returnResult();
        ProductView product = created.getResponseBody();
        assertThat(created.getResponseHeaders().getLocation()).hasToString("/api/v1/products/" + product.id());
        assertThat(product.slug()).startsWith("handloom-cushion-");
        assertThat(product.price()).isEqualByComparingTo("1299.00");

        // 2. read-your-write: served from Postgres while the projection catches up, and public
        client.get().uri("/api/v1/products/" + product.id()).exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.name").isEqualTo("Handloom Cushion");

        // 3. outbox -> Kafka -> MongoDB projection
        ProductDocument projected = awaitCatalogue(product.id(), doc -> doc.getName() != null);
        assertThat(projected.getCategory()).isEqualTo(category);
        assertThat(projected.getPrice()).isEqualByComparingTo("1299.00");

        // 4. public search over the read model
        client.get().uri("/api/v1/products?q=handloom&category=" + category).exchange()
                .expectStatus().isOk()
                .expectHeader().cacheControl(org.springframework.http.CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic())
                .expectBody()
                .jsonPath("$.total").isEqualTo(1)
                .jsonPath("$.items[0].id").isEqualTo(product.id());

        // 5. reprice, which reprojects
        Map<String, Object> repriced = Map.of("name", "Handloom Cushion", "description", "Woven in Bhuj",
                "brand", "Lailo Home", "category", category, "price", "1099.00", "stockQuantity", 12);
        send(HttpMethod.PUT, "/api/v1/products/" + product.id(), repriced, token)
                .expectStatus().isOk()
                .expectBody().jsonPath("$.price").isEqualTo(1099.00)
                .jsonPath("$.slug").isEqualTo(product.slug());
        assertThat(awaitCatalogue(product.id(), doc -> doc.getPrice().compareTo(new java.math.BigDecimal("1099.00")) == 0))
                .isNotNull();

        // 6. archive: gone from the catalogue, still readable directly
        send(HttpMethod.DELETE, "/api/v1/products/" + product.id(), null, token).expectStatus().isNoContent();
        awaitCatalogue(product.id(), doc -> "ARCHIVED".equals(doc.getStatus()));
        client.get().uri("/api/v1/products?category=" + category).exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.total").isEqualTo(0);
        client.get().uri("/api/v1/products/" + product.id()).exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("ARCHIVED");
    }

    /**
     * Catalogue writes are closed to anonymous callers, reads are open.
     * <ul>
     *   <li><b>Given</b> no access token</li>
     *   <li><b>When</b> a product is created with a CSRF token and then without one, and the catalogue is browsed</li>
     *   <li><b>Then</b> the writes get 401 and 403 while browsing gets 200</li>
     * </ul>
     */
    @Test
    void writesRequireSignInWhileBrowsingIsPublic() {
        Map<String, Object> body = Map.of("name", "Terracotta Vase", "category", ProductFixtures.uniqueCategory(),
                "price", "649.00", "stockQuantity", 3);

        send(HttpMethod.POST, "/api/v1/products", body, null).expectStatus().isUnauthorized();

        client.post().uri("/api/v1/products").contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange().expectStatus().isForbidden();

        client.get().uri("/api/v1/products").exchange().expectStatus().isOk();
    }

    /**
     * An image goes from the client straight into the object store and only then into the catalogue.
     * <ul>
     *   <li><b>Given</b> a signed-in customer and a product</li>
     *   <li><b>When</b> an upload is reserved, the bytes are PUT to the presigned URL by the client itself,
     *       the upload is confirmed, and the catalogue catches up</li>
     *   <li><b>Then</b> the object store received the bytes (the API never saw them), the product read and the
     *       MongoDB projection both carry the gallery item with its public URL, and deleting it removes the
     *       item from the catalogue again</li>
     * </ul>
     *
     * @throws IOException if the direct upload cannot be performed
     * @throws InterruptedException if the direct upload is interrupted
     */
    @Test
    void mediaIsUploadedDirectlyToTheObjectStoreThenPublished() throws IOException, InterruptedException {
        String token = accessToken();
        String category = ProductFixtures.uniqueCategory();
        ProductView product = send(HttpMethod.POST, "/api/v1/products", Map.of("name", "Jute Rug",
                        "brand", "Lailo Home", "category", category, "price", "3499.00", "stockQuantity", 4), token)
                .expectStatus().isCreated().expectBody(ProductView.class).returnResult().getResponseBody();
        byte[] bytes = "fake-webp-bytes".getBytes(StandardCharsets.UTF_8);

        // 1. reserve: the API signs a URL, it does not accept the file
        RequestProductMediaUploadCommand.Result ticket = send(HttpMethod.POST,
                "/api/v1/products/" + product.id() + "/media",
                Map.of("contentType", "image/webp", "sizeBytes", bytes.length), token)
                .expectStatus().isOk()
                .expectBody(RequestProductMediaUploadCommand.Result.class).returnResult().getResponseBody();
        assertThat(ticket.uploadUrl()).contains("X-Amz-Signature");

        // 2. the client uploads to the object store itself, with the headers it was told to send
        HttpResponse<Void> uploaded = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(ticket.uploadUrl()))
                        .header("Content-Type", ticket.headers().get("Content-Type"))
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes))
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        assertThat(uploaded.statusCode()).isEqualTo(200);

        // 3. confirm: verified against the store, then published
        String objectKey = "products/" + product.id() + "/" + ticket.mediaId() + ".webp";
        givenStoredObject(objectKey, "image/webp", bytes.length);
        send(HttpMethod.POST, ticket.confirmUrl(), null, token)
                .expectStatus().isOk()
                .expectBody().jsonPath("$.type").isEqualTo("IMAGE");

        // 4. visible to shoppers once the media event has been projected (the product read is served from the
        //    catalogue document as soon as that exists, so media follows the same ~1s lag)
        awaitCatalogue(product.id(), doc -> doc.getMedia() != null && doc.getMedia().containsKey(ticket.mediaId()));
        client.get().uri("/api/v1/products/" + product.id()).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.media[0].id").isEqualTo(ticket.mediaId())
                .jsonPath("$.media[0].url").value(org.hamcrest.Matchers.endsWith(objectKey))
                .jsonPath("$.media[0].contentType").isEqualTo("image/webp");
        client.get().uri("/api/v1/products?category=" + category).exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.items[0].media[0].url").value(org.hamcrest.Matchers.endsWith(objectKey));

        // 5. removing it takes it out of the catalogue again
        send(HttpMethod.DELETE, "/api/v1/products/" + product.id() + "/media/" + ticket.mediaId(), null, token)
                .expectStatus().isNoContent();
        awaitCatalogue(product.id(), doc -> doc.getMedia() == null || doc.getMedia().isEmpty());
    }
}
