package com.lailo.application.features.product.application.command;

import com.lailo.application.features.product.application.query.ProductView;
import com.lailo.application.features.product.infrastructure.persistence.ProductMediaRepository;
import com.lailo.application.support.EmbeddedInfrastructure;
import com.lailo.application.support.IntegrationTest;
import com.lailo.application.support.ProductFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.r2dbc.core.DatabaseClient;

import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/** Removing media: the row and its event commit first, then the blob is deleted from the object store. */
class DeleteProductMediaHandlerIT extends IntegrationTest {

    @Autowired CreateProductHandler createHandler;
    @Autowired RequestProductMediaUploadHandler requestHandler;
    @Autowired ConfirmProductMediaUploadHandler confirmHandler;
    @Autowired DeleteProductMediaHandler handler;
    @Autowired ProductMediaRepository media;
    @Autowired DatabaseClient db;

    /**
     * Creates a product with one reserved image upload.
     *
     * @return the product
     */
    private ProductView product() {
        return createHandler.handle(new CreateProductCommand(
                ProductFixtures.details("Cane Basket", ProductFixtures.uniqueCategory()))).block();
    }

    /**
     * Reserves one image upload on a product.
     *
     * @param productId owning product
     * @return the upload ticket
     */
    private RequestProductMediaUploadCommand.Result reserve(UUID productId) {
        return requestHandler.handle(RequestProductMediaUploadCommand.builder()
                .productId(productId).contentType("image/jpeg").sizeBytes(2048).build()).block();
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
     * Deleting published media removes the row, the blob and the catalogue entry.
     * <ul>
     *   <li><b>Given</b> a confirmed image</li>
     *   <li><b>When</b> it is deleted</li>
     *   <li><b>Then</b> the call reports {@code true}, the row is gone, a {@code ProductMediaRemoved} event is
     *       recorded and the object store received a delete for its key</li>
     * </ul>
     */
    @Test
    void deletesTheRowTheEventAndTheObject() {
        ProductView product = product();
        UUID productId = UUID.fromString(product.id());
        UUID mediaId = UUID.fromString(reserve(productId).mediaId());
        String key = media.findById(mediaId).block().getObjectKey();
        givenStoredObject(key, "image/jpeg", 2048);
        confirmHandler.handle(new ConfirmProductMediaUploadCommand(productId, mediaId)).block();

        assertThat(handler.handle(new DeleteProductMediaCommand(productId, mediaId)).block()).isTrue();

        assertThat(media.findById(mediaId).block()).isNull();
        assertThat(outboxEvents(product.id()))
                .containsExactly("ProductCreated", "ProductMediaAdded", "ProductMediaRemoved");
        EmbeddedInfrastructure.objectStorage()
                .verify(deleteRequestedFor(urlPathEqualTo("/lailo-media/" + key)));
    }

    /**
     * Abandoned uploads are cleaned up without telling the catalogue.
     * <ul>
     *   <li><b>Given</b> a reserved upload that was never confirmed</li>
     *   <li><b>When</b> it is deleted</li>
     *   <li><b>Then</b> the row is gone and no media event is recorded, because nothing was ever published</li>
     * </ul>
     */
    @Test
    void deletingAPendingUploadPublishesNothing() {
        ProductView product = product();
        UUID productId = UUID.fromString(product.id());
        UUID mediaId = UUID.fromString(reserve(productId).mediaId());

        assertThat(handler.handle(new DeleteProductMediaCommand(productId, mediaId)).block()).isTrue();

        assertThat(media.findById(mediaId).block()).isNull();
        assertThat(outboxEvents(product.id())).containsExactly("ProductCreated");
    }

    /**
     * Deleting twice, or deleting something that never existed, is not an error.
     * <ul>
     *   <li><b>Given</b> a random media id</li>
     *   <li><b>When</b> it is deleted</li>
     *   <li><b>Then</b> the call reports {@code false}</li>
     * </ul>
     */
    @Test
    void deletingUnknownMediaIsANoOp() {
        assertThat(handler.handle(new DeleteProductMediaCommand(
                UUID.fromString(product().id()), UUID.randomUUID())).block()).isFalse();
    }
}
