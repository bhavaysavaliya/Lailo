package com.lailo.application.features.product.application.command;

import com.lailo.application.common.web.ApiException;
import com.lailo.application.features.product.application.query.GetProductHandler;
import com.lailo.application.features.product.application.query.GetProductQuery;
import com.lailo.application.features.product.application.query.ProductMediaView;
import com.lailo.application.features.product.application.query.ProductView;
import com.lailo.application.features.product.domain.MediaStatus;
import com.lailo.application.features.product.infrastructure.persistence.ProductMediaRepository;
import com.lailo.application.support.IntegrationTest;
import com.lailo.application.support.ProductFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Confirming an upload against the (stubbed) object store: only verified bytes become catalogue content.
 */
class ConfirmProductMediaUploadHandlerIT extends IntegrationTest {

    @Autowired CreateProductHandler createHandler;
    @Autowired RequestProductMediaUploadHandler requestHandler;
    @Autowired ConfirmProductMediaUploadHandler handler;
    @Autowired GetProductHandler getProduct;
    @Autowired ProductMediaRepository media;
    @Autowired DatabaseClient db;

    /**
     * Creates a product and reserves one image upload for it.
     *
     * @return product and upload ticket
     */
    private Reserved reserve() {
        ProductView product = createHandler.handle(new CreateProductCommand(
                ProductFixtures.details("Clay Planter", ProductFixtures.uniqueCategory()))).block();
        RequestProductMediaUploadCommand.Result ticket = requestHandler.handle(
                RequestProductMediaUploadCommand.builder()
                        .productId(UUID.fromString(product.id()))
                        .contentType("image/webp")
                        .sizeBytes(4096)
                        .build()).block();
        return new Reserved(product, ticket);
    }

    /**
     * A product with a reserved upload.
     *
     * @param product the product
     * @param ticket the upload ticket
     */
    private record Reserved(ProductView product, RequestProductMediaUploadCommand.Result ticket) {
    }

    /**
     * Reads the event types recorded for a product, oldest first.
     *
     * @param productId aggregate id
     * @return the recorded event types
     */
    private List<String> outboxEvents(String productId) {
        return db.sql("SELECT event_type FROM outbox_messages WHERE aggregate_id = :id ORDER BY created_at")
                .bind("id", productId).map(r -> r.get(0, String.class)).all().collectList().block();
    }

    /**
     * Confirmed bytes publish the gallery item.
     * <ul>
     *   <li><b>Given</b> a reserved upload whose object the store now reports</li>
     *   <li><b>When</b> the upload is confirmed</li>
     *   <li><b>Then</b> the row becomes {@code READY} with a ready timestamp, a {@code ProductMediaAdded}
     *       event is recorded, and the returned view carries the public URL built from the object key</li>
     * </ul>
     */
    @Test
    void publishesMediaOnceTheBytesExist() {
        Reserved r = reserve();
        String key = media.findById(UUID.fromString(r.ticket().mediaId())).block().getObjectKey();
        givenStoredObject(key, "image/webp", 4096);

        ProductMediaView view = handler.handle(new ConfirmProductMediaUploadCommand(
                UUID.fromString(r.product().id()), UUID.fromString(r.ticket().mediaId()))).block();

        assertThat(view.type()).isEqualTo("IMAGE");
        assertThat(view.url()).endsWith("/lailo-media/" + key);
        assertThat(view.sizeBytes()).isEqualTo(4096);

        var stored = media.findById(UUID.fromString(r.ticket().mediaId())).block();
        assertThat(stored.getStatus()).isEqualTo(MediaStatus.READY);
        assertThat(stored.getReadyAt()).isNotNull();
        assertThat(outboxEvents(r.product().id())).containsExactly("ProductCreated", "ProductMediaAdded");
    }

    /**
     * Confirmed media is part of the product read, before any projection has run.
     * <ul>
     *   <li><b>Given</b> a confirmed image on a product</li>
     *   <li><b>When</b> the product is read while the Mongo projection is still behind</li>
     *   <li><b>Then</b> the Postgres fallback already includes the gallery item</li>
     * </ul>
     */
    @Test
    void mediaAppearsInTheProductReadImmediately() {
        Reserved r = reserve();
        String key = media.findById(UUID.fromString(r.ticket().mediaId())).block().getObjectKey();
        givenStoredObject(key, "image/webp", 4096);
        handler.handle(new ConfirmProductMediaUploadCommand(UUID.fromString(r.product().id()),
                UUID.fromString(r.ticket().mediaId()))).block();

        ProductView view = getProduct.handle(new GetProductQuery(UUID.fromString(r.product().id()))).block();

        assertThat(view.media()).hasSize(1);
        assertThat(view.media().getFirst().url()).endsWith(key);
    }

    /**
     * An upload that never happened is not published.
     * <ul>
     *   <li><b>Given</b> a reserved upload with no object in the store</li>
     *   <li><b>When</b> it is confirmed</li>
     *   <li><b>Then</b> it fails with {@code MEDIA_UPLOAD_INCOMPLETE}, the row stays {@code PENDING} and
     *       nothing is published</li>
     * </ul>
     */
    @Test
    void refusesToPublishMissingBytes() {
        Reserved r = reserve();

        StepVerifier.create(handler.handle(new ConfirmProductMediaUploadCommand(
                        UUID.fromString(r.product().id()), UUID.fromString(r.ticket().mediaId()))))
                .expectErrorSatisfies(e ->
                        assertThat(((ApiException) e).getCode()).isEqualTo("MEDIA_UPLOAD_INCOMPLETE"))
                .verify();

        assertThat(media.findById(UUID.fromString(r.ticket().mediaId())).block().getStatus())
                .isEqualTo(MediaStatus.PENDING);
        assertThat(outboxEvents(r.product().id())).containsExactly("ProductCreated");
    }

    /**
     * Bytes that are not what was signed for are not published.
     * <ul>
     *   <li><b>Given</b> a reserved WebP upload whose stored object is a PNG</li>
     *   <li><b>When</b> it is confirmed</li>
     *   <li><b>Then</b> it fails with {@code MEDIA_CONTENT_MISMATCH} and the row stays {@code PENDING}</li>
     * </ul>
     */
    @Test
    void refusesContentThatDoesNotMatchTheReservation() {
        Reserved r = reserve();
        String key = media.findById(UUID.fromString(r.ticket().mediaId())).block().getObjectKey();
        givenStoredObject(key, "image/png", 4096);

        StepVerifier.create(handler.handle(new ConfirmProductMediaUploadCommand(
                        UUID.fromString(r.product().id()), UUID.fromString(r.ticket().mediaId()))))
                .expectErrorSatisfies(e ->
                        assertThat(((ApiException) e).getCode()).isEqualTo("MEDIA_CONTENT_MISMATCH"))
                .verify();

        assertThat(media.findById(UUID.fromString(r.ticket().mediaId())).block().getStatus())
                .isEqualTo(MediaStatus.PENDING);
    }

    /**
     * Confirming twice is harmless.
     * <ul>
     *   <li><b>Given</b> an already confirmed image</li>
     *   <li><b>When</b> it is confirmed again</li>
     *   <li><b>Then</b> the same item is returned and only one {@code ProductMediaAdded} event exists</li>
     * </ul>
     */
    @Test
    void confirmingTwiceIsIdempotent() {
        Reserved r = reserve();
        UUID productId = UUID.fromString(r.product().id());
        UUID mediaId = UUID.fromString(r.ticket().mediaId());
        givenStoredObject(media.findById(mediaId).block().getObjectKey(), "image/webp", 4096);
        ProductMediaView first = handler.handle(new ConfirmProductMediaUploadCommand(productId, mediaId)).block();

        ProductMediaView again = handler.handle(new ConfirmProductMediaUploadCommand(productId, mediaId)).block();

        assertThat(again).isEqualTo(first);
        assertThat(outboxEvents(r.product().id())).containsExactly("ProductCreated", "ProductMediaAdded");
    }

    /**
     * Media of another product cannot be confirmed through this product.
     * <ul>
     *   <li><b>Given</b> a media item reserved on one product</li>
     *   <li><b>When</b> a different product id is used to confirm it</li>
     *   <li><b>Then</b> it fails with {@code MEDIA_NOT_FOUND}</li>
     * </ul>
     */
    @Test
    void mediaIsScopedToItsProduct() {
        Reserved r = reserve();

        StepVerifier.create(handler.handle(new ConfirmProductMediaUploadCommand(
                        UUID.randomUUID(), UUID.fromString(r.ticket().mediaId()))))
                .expectErrorSatisfies(e -> assertThat(((ApiException) e).getCode()).isEqualTo("MEDIA_NOT_FOUND"))
                .verify();
    }
}
