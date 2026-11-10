package com.lailo.application.features.product.api;

import com.lailo.application.common.cqrs.CommandBus;
import com.lailo.application.common.cqrs.QueryBus;
import com.lailo.application.features.auth.config.AuthBeansConfig;
import com.lailo.application.features.product.application.ProductErrors;
import com.lailo.application.features.product.application.command.ConfirmProductMediaUploadCommand;
import com.lailo.application.features.product.application.command.DeleteProductMediaCommand;
import com.lailo.application.features.product.application.command.RequestProductMediaUploadCommand;
import com.lailo.application.features.product.application.query.ProductMediaView;
import com.lailo.application.security.auth.config.AuthConfig;
import com.lailo.application.support.TestAuthProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.csrf;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

/** HTTP contract of the media API: auth, CSRF, validation, and the upload ticket it hands back. */
@WebFluxTest(controllers = ProductMediaController.class)
@Import({AuthConfig.class, AuthBeansConfig.class})
@TestPropertySource(properties = {
        "lailo.auth.otp.hash-secret=" + TestAuthProperties.OTP_SECRET,
        "lailo.auth.jwt.secret=" + TestAuthProperties.JWT_SECRET
})
class ProductMediaControllerTest {

    private static final UUID PRODUCT_ID = UUID.fromString("11111111-2222-4333-8444-555555555555");
    private static final UUID MEDIA_ID = UUID.fromString("66666666-7777-4888-8999-aaaaaaaaaaaa");

    @Autowired WebTestClient client;
    @MockitoBean CommandBus commandBus;
    @MockitoBean QueryBus queryBus;

    /**
     * An upload ticket as the handler would return it.
     *
     * @return the ticket
     */
    private static RequestProductMediaUploadCommand.Result ticket() {
        return RequestProductMediaUploadCommand.Result.builder()
                .mediaId(MEDIA_ID.toString())
                .type("VIDEO")
                .uploadUrl("http://localhost:8333/lailo-media/products/p/m.mp4?X-Amz-Signature=abc")
                .httpMethod("PUT")
                .headers(Map.of("Content-Type", "video/mp4", "Content-Length", "1048576"))
                .expiresAt(Instant.parse("2026-11-10T10:10:00Z"))
                .confirmUrl("/api/v1/products/" + PRODUCT_ID + "/media/" + MEDIA_ID)
                .build();
    }

    /**
     * A valid body.
     *
     * @return the request body
     */
    private static Map<String, Object> body() {
        return Map.of("contentType", "video/mp4", "sizeBytes", 1_048_576);
    }

    /**
     * Reserving an upload is only for signed-in callers and needs a CSRF token.
     * <ul>
     *   <li><b>Given</b> a valid body</li>
     *   <li><b>When</b> it is posted anonymously with a CSRF token, then without one</li>
     *   <li><b>Then</b> the calls get 401 and 403, and no command is dispatched</li>
     * </ul>
     */
    @Test
    void requestUploadRejectsAnonymousAndCsrfLessCalls() {
        client.mutateWith(csrf()).post().uri("/api/v1/products/" + PRODUCT_ID + "/media")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body())
                .exchange().expectStatus().isUnauthorized();

        client.post().uri("/api/v1/products/" + PRODUCT_ID + "/media")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body())
                .exchange().expectStatus().isForbidden();

        verifyNoInteractions(commandBus);
    }

    /**
     * A signed-in caller gets a presigned ticket that is never cached.
     * <ul>
     *   <li><b>Given</b> the command bus returns an upload ticket</li>
     *   <li><b>When</b> an authenticated request reserves a 1 MB MP4 upload</li>
     *   <li><b>Then</b> status is 200 with {@code Cache-Control: no-store}, the body carries the URL, method,
     *       headers and confirm URL, and the command repeats the declared type and size</li>
     * </ul>
     */
    @Test
    void requestUploadReturnsAnUncachedTicket() {
        when(commandBus.dispatch(any(RequestProductMediaUploadCommand.class))).thenReturn(Mono.just(ticket()));

        client.mutateWith(mockJwt()).mutateWith(csrf()).post().uri("/api/v1/products/" + PRODUCT_ID + "/media")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body())
                .exchange()
                .expectStatus().isOk()
                .expectHeader().cacheControl(CacheControl.noStore())
                .expectBody()
                .jsonPath("$.mediaId").isEqualTo(MEDIA_ID.toString())
                .jsonPath("$.type").isEqualTo("VIDEO")
                .jsonPath("$.httpMethod").isEqualTo("PUT")
                .jsonPath("$.uploadUrl").value(org.hamcrest.Matchers.containsString("X-Amz-Signature"))
                .jsonPath("$.headers['Content-Type']").isEqualTo("video/mp4")
                .jsonPath("$.confirmUrl").isEqualTo("/api/v1/products/" + PRODUCT_ID + "/media/" + MEDIA_ID);

        ArgumentCaptor<RequestProductMediaUploadCommand> captor =
                ArgumentCaptor.forClass(RequestProductMediaUploadCommand.class);
        verify(commandBus).dispatch(captor.capture());
        assertThat(captor.getValue().productId()).isEqualTo(PRODUCT_ID);
        assertThat(captor.getValue().contentType()).isEqualTo("video/mp4");
        assertThat(captor.getValue().sizeBytes()).isEqualTo(1_048_576);
    }

    /**
     * A body without a file type or size never reaches a handler.
     * <ul>
     *   <li><b>Given</b> a body with a blank content type and a zero size</li>
     *   <li><b>When</b> it is posted by a signed-in caller</li>
     *   <li><b>Then</b> status is 400 {@code VALIDATION_FAILED} naming both fields, and the bus is untouched</li>
     * </ul>
     */
    @Test
    void invalidBodyIsRejected() {
        client.mutateWith(mockJwt()).mutateWith(csrf()).post().uri("/api/v1/products/" + PRODUCT_ID + "/media")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("contentType", " ", "sizeBytes", 0))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("VALIDATION_FAILED")
                .jsonPath("$.errors.contentType").exists()
                .jsonPath("$.errors.sizeBytes").exists();

        verifyNoInteractions(commandBus);
    }

    /**
     * A rejected file type is reported as a problem response, not a generic failure.
     * <ul>
     *   <li><b>Given</b> the command bus fails with {@code MEDIA_TYPE_UNSUPPORTED}</li>
     *   <li><b>When</b> an upload is requested</li>
     *   <li><b>Then</b> status is 415 and the body carries the code and the offending content type</li>
     * </ul>
     */
    @Test
    void unsupportedTypeIsReportedAsProblemDetail() {
        when(commandBus.dispatch(any(RequestProductMediaUploadCommand.class)))
                .thenReturn(Mono.error(ProductErrors.mediaTypeUnsupported("image/svg+xml")));

        client.mutateWith(mockJwt()).mutateWith(csrf()).post().uri("/api/v1/products/" + PRODUCT_ID + "/media")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("contentType", "image/svg+xml", "sizeBytes", 1024))
                .exchange()
                .expectStatus().isEqualTo(415)
                .expectBody()
                .jsonPath("$.code").isEqualTo("MEDIA_TYPE_UNSUPPORTED")
                .jsonPath("$.contentType").isEqualTo("image/svg+xml");
    }

    /**
     * Confirming an upload returns the published gallery item.
     * <ul>
     *   <li><b>Given</b> the command bus returns a ready media item</li>
     *   <li><b>When</b> an authenticated confirm is posted</li>
     *   <li><b>Then</b> status is 200 with the item's public URL, and the command carries both ids</li>
     * </ul>
     */
    @Test
    void confirmUploadReturnsThePublishedItem() {
        when(commandBus.dispatch(new ConfirmProductMediaUploadCommand(PRODUCT_ID, MEDIA_ID)))
                .thenReturn(Mono.just(ProductMediaView.builder()
                        .id(MEDIA_ID.toString())
                        .type("VIDEO")
                        .url("http://localhost:8333/lailo-media/products/p/m.mp4")
                        .contentType("video/mp4")
                        .sizeBytes(1_048_576)
                        .position(0)
                        .build()));

        client.mutateWith(mockJwt()).mutateWith(csrf())
                .post().uri("/api/v1/products/" + PRODUCT_ID + "/media/" + MEDIA_ID)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo(MEDIA_ID.toString())
                .jsonPath("$.url").isEqualTo("http://localhost:8333/lailo-media/products/p/m.mp4");

        verify(commandBus).dispatch(new ConfirmProductMediaUploadCommand(PRODUCT_ID, MEDIA_ID));
    }

    /**
     * Deleting media answers 204 whether or not there was anything to delete.
     * <ul>
     *   <li><b>Given</b> the command bus reports nothing was removed</li>
     *   <li><b>When</b> an authenticated delete is sent</li>
     *   <li><b>Then</b> status is 204 with an empty body and the command was dispatched</li>
     * </ul>
     */
    @Test
    void deleteIsIdempotentAndAnswersNoContent() {
        when(commandBus.dispatch(new DeleteProductMediaCommand(PRODUCT_ID, MEDIA_ID)))
                .thenReturn(Mono.just(false));

        client.mutateWith(mockJwt()).mutateWith(csrf())
                .delete().uri("/api/v1/products/" + PRODUCT_ID + "/media/" + MEDIA_ID)
                .exchange()
                .expectStatus().isNoContent()
                .expectBody().isEmpty();

        verify(commandBus).dispatch(new DeleteProductMediaCommand(PRODUCT_ID, MEDIA_ID));
    }
}
