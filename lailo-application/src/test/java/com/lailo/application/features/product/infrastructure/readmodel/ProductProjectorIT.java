package com.lailo.application.features.product.infrastructure.readmodel;

import com.lailo.application.features.product.domain.event.ProductEvents;
import com.lailo.application.support.IntegrationTest;
import com.lailo.application.support.ProductFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Projection rules against real MongoDB. Kafka delivery is at-least-once and a retry can replay an older
 * event, so the projector must be both idempotent and immune to out-of-order delivery.
 */
class ProductProjectorIT extends IntegrationTest {

    private static final Instant T = Instant.parse("2026-11-08T10:00:00Z");

    @Autowired ProductProjector projector;
    @Autowired ProductCatalogRepository catalog;

    /**
     * Builds a creation event for a product.
     *
     * @param id product id
     * @param name display name
     * @param price price in rupees
     * @param occurredAt event time
     * @return the event
     */
    private static ProductEvents.ProductCreated created(String id, String name, String price, Instant occurredAt) {
        return ProductEvents.ProductCreated.builder()
                .eventId(UUID.randomUUID().toString())
                .productId(id)
                .slug("slug-" + id.substring(0, 8))
                .name(name)
                .description(name + " description")
                .brand("Lailo Basics")
                .category(ProductFixtures.uniqueCategory())
                .price(new BigDecimal(price))
                .currency("INR")
                .stockQuantity(5)
                .occurredAt(occurredAt)
                .build();
    }

    /**
     * Builds an update event for a product.
     *
     * @param id product id
     * @param name display name
     * @param price price in rupees
     * @param occurredAt event time
     * @return the event
     */
    private static ProductEvents.ProductUpdated updated(String id, String name, String price, Instant occurredAt) {
        return ProductEvents.ProductUpdated.builder()
                .eventId(UUID.randomUUID().toString())
                .productId(id)
                .slug("slug-" + id.substring(0, 8))
                .name(name)
                .description(name + " description")
                .brand("Lailo Basics")
                .category(ProductFixtures.uniqueCategory())
                .price(new BigDecimal(price))
                .currency("INR")
                .stockQuantity(5)
                .occurredAt(occurredAt)
                .build();
    }

    /**
     * Creation builds the document and an update overwrites the details.
     * <ul>
     *   <li><b>Given</b> a creation event followed by an update event</li>
     *   <li><b>When</b> both are projected in order</li>
     *   <li><b>Then</b> the document carries the updated name and price, keeps the original {@code createdAt}
     *       and is {@code ACTIVE}</li>
     * </ul>
     */
    @Test
    void projectsCreationThenUpdate() {
        String id = UUID.randomUUID().toString();

        projector.on(created(id, "Jute Bag", "299.00", T)).block();
        projector.on(updated(id, "Jute Bag Large", "349.00", T.plusSeconds(30))).block();

        ProductDocument doc = catalog.findById(id).block();
        assertThat(doc.getName()).isEqualTo("Jute Bag Large");
        assertThat(doc.getPrice()).isEqualByComparingTo("349.00");
        assertThat(doc.getCreatedAt()).isEqualTo(T);
        assertThat(doc.getUpdatedAt()).isEqualTo(T.plusSeconds(30));
        assertThat(doc.getStatus()).isEqualTo("ACTIVE");
    }

    /**
     * A replayed older event cannot resurrect stale data.
     * <ul>
     *   <li><b>Given</b> a document already projected from an update at T+30</li>
     *   <li><b>When</b> the original creation event from T is delivered again</li>
     *   <li><b>Then</b> the document is unchanged</li>
     * </ul>
     */
    @Test
    void ignoresReplayedOlderEvents() {
        String id = UUID.randomUUID().toString();
        ProductEvents.ProductCreated create = created(id, "Jute Bag", "299.00", T);
        projector.on(create).block();
        projector.on(updated(id, "Jute Bag Large", "349.00", T.plusSeconds(30))).block();

        projector.on(create).block();

        ProductDocument doc = catalog.findById(id).block();
        assertThat(doc.getName()).isEqualTo("Jute Bag Large");
        assertThat(doc.getPrice()).isEqualByComparingTo("349.00");
        assertThat(doc.getUpdatedAt()).isEqualTo(T.plusSeconds(30));
    }

    /**
     * Replaying the same event twice changes nothing the second time.
     * <ul>
     *   <li><b>Given</b> a creation event</li>
     *   <li><b>When</b> it is projected twice</li>
     *   <li><b>Then</b> exactly one document exists with that state</li>
     * </ul>
     */
    @Test
    void isIdempotentForTheSameEvent() {
        String id = UUID.randomUUID().toString();
        ProductEvents.ProductCreated create = created(id, "Steel Tiffin", "799.00", T);

        projector.on(create).block();
        projector.on(create).block();

        assertThat(catalog.count().block()).isNotNull();
        ProductDocument doc = catalog.findById(id).block();
        assertThat(doc.getName()).isEqualTo("Steel Tiffin");
        assertThat(doc.getUpdatedAt()).isEqualTo(T);
    }

    /**
     * An update that arrives before its creation event still produces a usable entry.
     * <ul>
     *   <li><b>Given</b> no document for the product</li>
     *   <li><b>When</b> an update event from T+30 is projected, then the creation event from T</li>
     *   <li><b>Then</b> the document is created from the update, marked {@code ACTIVE}, and the late creation
     *       event does not overwrite it</li>
     * </ul>
     */
    @Test
    void handlesAnUpdateArrivingBeforeItsCreation() {
        String id = UUID.randomUUID().toString();

        projector.on(updated(id, "Cane Chair Pro", "5499.00", T.plusSeconds(30))).block();
        projector.on(created(id, "Cane Chair", "4999.00", T)).block();

        ProductDocument doc = catalog.findById(id).block();
        assertThat(doc.getName()).isEqualTo("Cane Chair Pro");
        assertThat(doc.getStatus()).isEqualTo("ACTIVE");
        assertThat(doc.getCreatedAt()).isEqualTo(T.plusSeconds(30));
    }

    /**
     * Archiving removes the entry from the catalogue without deleting it.
     * <ul>
     *   <li><b>Given</b> an active document</li>
     *   <li><b>When</b> an archive event is projected, then an older update event is replayed</li>
     *   <li><b>Then</b> the document stays {@code ARCHIVED}</li>
     * </ul>
     */
    @Test
    void archivesAndStaysArchivedOnLateUpdates() {
        String id = UUID.randomUUID().toString();
        projector.on(created(id, "Paper Lamp", "899.00", T)).block();

        projector.on(new ProductEvents.ProductArchived(UUID.randomUUID().toString(), id, T.plusSeconds(60))).block();
        projector.on(updated(id, "Paper Lamp", "899.00", T.plusSeconds(10))).block();

        ProductDocument doc = catalog.findById(id).block();
        assertThat(doc.getStatus()).isEqualTo("ARCHIVED");
        assertThat(doc.getUpdatedAt()).isEqualTo(T.plusSeconds(60));
    }

    /**
     * Media is projected under its own id, so a redelivery rewrites rather than duplicates.
     * <ul>
     *   <li><b>Given</b> a projected product</li>
     *   <li><b>When</b> a media-added event is projected twice and a second item is added</li>
     *   <li><b>Then</b> the gallery holds exactly two items, keyed by media id, with their positions</li>
     * </ul>
     */
    @Test
    void projectsMediaKeyedByIdAndIsIdempotent() {
        String id = UUID.randomUUID().toString();
        projector.on(created(id, "Rattan Stool", "2499.00", T)).block();
        String firstMedia = UUID.randomUUID().toString();
        ProductEvents.ProductMediaAdded added = ProductEvents.ProductMediaAdded.builder()
                .eventId(UUID.randomUUID().toString())
                .productId(id)
                .mediaId(firstMedia)
                .type("IMAGE")
                .objectKey("products/" + id + "/" + firstMedia + ".webp")
                .contentType("image/webp")
                .sizeBytes(4096)
                .position(0)
                .occurredAt(T.plusSeconds(10))
                .build();

        projector.on(added).block();
        projector.on(added).block();
        String secondMedia = UUID.randomUUID().toString();
        projector.on(ProductEvents.ProductMediaAdded.builder()
                .eventId(UUID.randomUUID().toString())
                .productId(id)
                .mediaId(secondMedia)
                .type("VIDEO")
                .objectKey("products/" + id + "/" + secondMedia + ".mp4")
                .contentType("video/mp4")
                .sizeBytes(50_000_000)
                .position(1)
                .occurredAt(T.plusSeconds(20))
                .build()).block();

        ProductDocument doc = catalog.findById(id).block();
        assertThat(doc.getMedia()).hasSize(2).containsKeys(firstMedia, secondMedia);
        assertThat(doc.getMedia().get(firstMedia).getContentType()).isEqualTo("image/webp");
        assertThat(doc.getMedia().get(secondMedia).getType()).isEqualTo("VIDEO");
        assertThat(doc.getMedia().get(secondMedia).getPosition()).isEqualTo(1);
    }

    /**
     * Removing media drops only that item from the gallery.
     * <ul>
     *   <li><b>Given</b> a product with one projected image</li>
     *   <li><b>When</b> a media-removed event is projected</li>
     *   <li><b>Then</b> the gallery is empty and the product itself is untouched</li>
     * </ul>
     */
    @Test
    void projectsMediaRemoval() {
        String id = UUID.randomUUID().toString();
        String mediaId = UUID.randomUUID().toString();
        projector.on(created(id, "Rattan Stool", "2499.00", T)).block();
        projector.on(ProductEvents.ProductMediaAdded.builder()
                .eventId(UUID.randomUUID().toString())
                .productId(id).mediaId(mediaId).type("IMAGE")
                .objectKey("products/" + id + "/" + mediaId + ".webp")
                .contentType("image/webp").sizeBytes(4096).position(0)
                .occurredAt(T.plusSeconds(10))
                .build()).block();

        projector.on(new ProductEvents.ProductMediaRemoved(UUID.randomUUID().toString(), id, mediaId,
                T.plusSeconds(20))).block();

        ProductDocument doc = catalog.findById(id).block();
        assertThat(doc.getMedia()).isEmpty();
        assertThat(doc.getName()).isEqualTo("Rattan Stool");
        assertThat(doc.getStatus()).isEqualTo("ACTIVE");
    }
}
