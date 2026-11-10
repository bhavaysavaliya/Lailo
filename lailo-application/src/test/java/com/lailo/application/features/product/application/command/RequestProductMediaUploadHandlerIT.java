package com.lailo.application.features.product.application.command;

import com.lailo.application.common.web.ApiException;
import com.lailo.application.features.product.application.query.ProductView;
import com.lailo.application.features.product.domain.MediaStatus;
import com.lailo.application.features.product.domain.ProductMedia;
import com.lailo.application.features.product.infrastructure.persistence.ProductMediaRepository;
import com.lailo.application.support.IntegrationTest;
import com.lailo.application.support.ProductFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reserving an upload: real Postgres for the pending row, real local signing for the URL. Nothing is
 * published, because reserved bytes are not catalogue content yet.
 */
class RequestProductMediaUploadHandlerIT extends IntegrationTest {

    @Autowired CreateProductHandler createHandler;
    @Autowired ArchiveProductHandler archiveHandler;
    @Autowired RequestProductMediaUploadHandler handler;
    @Autowired ProductMediaRepository media;
    @Autowired DatabaseClient db;

    /**
     * Creates a product to hang media on.
     *
     * @return the created product
     */
    private ProductView product() {
        return createHandler.handle(new CreateProductCommand(
                ProductFixtures.details("Handloom Cushion", ProductFixtures.uniqueCategory()))).block();
    }

    /**
     * Counts outbox rows for a product, to prove nothing was published.
     *
     * @param productId aggregate id
     * @return number of recorded events
     */
    private Long outboxCount(String productId) {
        return db.sql("SELECT count(*) FROM outbox_messages WHERE aggregate_id = :id")
                .bind("id", productId).map(r -> r.get(0, Long.class)).one().block();
    }

    /**
     * A reserved upload stores a pending row and returns a signed URL for its key.
     * <ul>
     *   <li><b>Given</b> an active product</li>
     *   <li><b>When</b> a 2 MB WebP upload is requested</li>
     *   <li><b>Then</b> the row is {@code PENDING} at position 0 with the content type and size declared, the
     *       URL points at {@code products/{productId}/{mediaId}.webp} and carries a signature, the client is
     *       told which headers to send, and no event was published</li>
     * </ul>
     */
    @Test
    void reservesAPendingRowAndSignsTheUrl() {
        ProductView product = product();
        long before = outboxCount(product.id());

        RequestProductMediaUploadCommand.Result ticket = handler.handle(RequestProductMediaUploadCommand.builder()
                .productId(UUID.fromString(product.id()))
                .contentType("image/webp")
                .sizeBytes(2_097_152)
                .build()).block();

        assertThat(ticket.type()).isEqualTo("IMAGE");
        assertThat(ticket.httpMethod()).isEqualTo("PUT");
        assertThat(ticket.headers()).containsEntry("Content-Type", "image/webp")
                .containsEntry("Content-Length", "2097152");
        assertThat(ticket.uploadUrl())
                .contains("/lailo-media/products/" + product.id() + "/" + ticket.mediaId() + ".webp")
                .contains("X-Amz-Signature");
        assertThat(ticket.expiresAt()).isNotNull();
        assertThat(ticket.confirmUrl()).isEqualTo("/api/v1/products/" + product.id() + "/media/" + ticket.mediaId());

        ProductMedia stored = media.findById(UUID.fromString(ticket.mediaId())).block();
        assertThat(stored.getStatus()).isEqualTo(MediaStatus.PENDING);
        assertThat(stored.getPosition()).isZero();
        assertThat(stored.getSizeBytes()).isEqualTo(2_097_152);
        assertThat(stored.getContentType()).isEqualTo("image/webp");
        assertThat(stored.getReadyAt()).isNull();
        assertThat(outboxCount(product.id())).isEqualTo(before);
    }

    /**
     * Each upload is appended to the end of the gallery.
     * <ul>
     *   <li><b>Given</b> a product with one reserved image</li>
     *   <li><b>When</b> a video upload is requested as well</li>
     *   <li><b>Then</b> it is classified as a video and takes position 1</li>
     * </ul>
     */
    @Test
    void appendsEachUploadAfterTheExistingOnes() {
        UUID productId = UUID.fromString(product().id());
        handler.handle(RequestProductMediaUploadCommand.builder()
                .productId(productId).contentType("image/jpeg").sizeBytes(1024).build()).block();

        RequestProductMediaUploadCommand.Result second = handler.handle(RequestProductMediaUploadCommand.builder()
                .productId(productId).contentType("video/mp4").sizeBytes(50_000_000).build()).block();

        assertThat(second.type()).isEqualTo("VIDEO");
        assertThat(media.findById(UUID.fromString(second.mediaId())).block().getPosition()).isEqualTo(1);
    }

    /**
     * File types Lailo does not store are refused before anything is reserved.
     * <ul>
     *   <li><b>Given</b> an active product</li>
     *   <li><b>When</b> an SVG upload is requested</li>
     *   <li><b>Then</b> it fails with {@code MEDIA_TYPE_UNSUPPORTED} and no row is created</li>
     * </ul>
     */
    @Test
    void refusesUnsupportedTypes() {
        UUID productId = UUID.fromString(product().id());

        StepVerifier.create(handler.handle(RequestProductMediaUploadCommand.builder()
                        .productId(productId).contentType("image/svg+xml").sizeBytes(1024).build()))
                .expectErrorSatisfies(e ->
                        assertThat(((ApiException) e).getCode()).isEqualTo("MEDIA_TYPE_UNSUPPORTED"))
                .verify();

        assertThat(media.countByProductId(productId).block()).isZero();
    }

    /**
     * The size limit depends on the kind of media.
     * <ul>
     *   <li><b>Given</b> the configured 10 MB image and 300 MB video limits</li>
     *   <li><b>When</b> a 20 MB image and then a 20 MB video are requested</li>
     *   <li><b>Then</b> the image fails with {@code MEDIA_TOO_LARGE} while the video is accepted</li>
     * </ul>
     */
    @Test
    void appliesTheLimitOfTheMediaKind() {
        UUID productId = UUID.fromString(product().id());
        long twentyMb = 20L * 1024 * 1024;

        StepVerifier.create(handler.handle(RequestProductMediaUploadCommand.builder()
                        .productId(productId).contentType("image/png").sizeBytes(twentyMb).build()))
                .expectErrorSatisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("MEDIA_TOO_LARGE"))
                .verify();

        assertThat(handler.handle(RequestProductMediaUploadCommand.builder()
                .productId(productId).contentType("video/mp4").sizeBytes(twentyMb).build()).block()).isNotNull();
    }

    /**
     * An archived product takes no new media.
     * <ul>
     *   <li><b>Given</b> a product that has been archived</li>
     *   <li><b>When</b> an upload is requested for it</li>
     *   <li><b>Then</b> it fails with {@code PRODUCT_ARCHIVED}</li>
     * </ul>
     */
    @Test
    void refusesMediaForArchivedProducts() {
        UUID productId = UUID.fromString(product().id());
        archiveHandler.handle(new ArchiveProductCommand(productId)).block();

        StepVerifier.create(handler.handle(RequestProductMediaUploadCommand.builder()
                        .productId(productId).contentType("image/jpeg").sizeBytes(1024).build()))
                .expectErrorSatisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("PRODUCT_ARCHIVED"))
                .verify();
    }

    /**
     * An unknown product cannot be given media.
     * <ul>
     *   <li><b>Given</b> a random product id</li>
     *   <li><b>When</b> an upload is requested</li>
     *   <li><b>Then</b> it fails with {@code PRODUCT_NOT_FOUND}</li>
     * </ul>
     */
    @Test
    void refusesMediaForUnknownProducts() {
        StepVerifier.create(handler.handle(RequestProductMediaUploadCommand.builder()
                        .productId(UUID.randomUUID()).contentType("image/jpeg").sizeBytes(1024).build()))
                .expectErrorSatisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("PRODUCT_NOT_FOUND"))
                .verify();
    }
}
