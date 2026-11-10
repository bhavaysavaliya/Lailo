package com.lailo.application.features.product.application.query;

import com.lailo.application.common.storage.ObjectStorage;
import com.lailo.application.features.product.domain.Product;
import com.lailo.application.features.product.domain.ProductMedia;
import com.lailo.application.features.product.infrastructure.readmodel.ProductDocument;
import com.lailo.application.features.product.infrastructure.readmodel.ProductMediaDocument;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Builds API views from either side of the CQRS split. Media URLs are derived here from the stored object
 * key, so pointing {@code lailo.storage.public-base-url} at a CDN changes every URL at once without touching
 * stored data.
 */
@Component
@RequiredArgsConstructor
public class ProductViewMapper {

    private final ObjectStorage storage;

    /**
     * Maps the write-model aggregate, which is how a command returns the state it just wrote.
     *
     * @param p the aggregate
     * @param media the product's ready media, in any order
     * @return the view
     */
    public ProductView fromAggregate(Product p, List<ProductMedia> media) {
        return ProductView.builder()
                .id(p.getId().toString())
                .slug(p.getSlug())
                .name(p.getName())
                .description(p.getDescription())
                .brand(p.getBrand())
                .category(p.getCategory())
                .price(p.getPrice())
                .currency(p.getCurrency())
                .stockQuantity(p.getStockQuantity())
                .status(p.getStatus().name())
                .createdAt(p.getCreatedAt())
                .updatedAt(p.getUpdatedAt())
                .media(media.stream()
                        .sorted(Comparator.comparingInt(ProductMedia::getPosition))
                        .map(this::toView)
                        .toList())
                .build();
    }

    /**
     * Maps a catalogue document.
     *
     * @param d the read-model document
     * @return the view
     */
    public ProductView fromDocument(ProductDocument d) {
        return ProductView.builder()
                .id(d.getId())
                .slug(d.getSlug())
                .name(d.getName())
                .description(d.getDescription())
                .brand(d.getBrand())
                .category(d.getCategory())
                .price(d.getPrice())
                .currency(d.getCurrency())
                .stockQuantity(d.getStockQuantity() == null ? 0 : d.getStockQuantity())
                .status(d.getStatus())
                .createdAt(d.getCreatedAt())
                .updatedAt(d.getUpdatedAt())
                .media(fromDocumentMedia(d.getMedia()))
                .build();
    }

    /**
     * Maps one media row.
     *
     * @param m the media item
     * @return the view, with the public URL filled in
     */
    public ProductMediaView toView(ProductMedia m) {
        return ProductMediaView.builder()
                .id(m.getId().toString())
                .type(m.getType().name())
                .url(storage.publicUrl(m.getObjectKey()))
                .contentType(m.getContentType())
                .sizeBytes(m.getSizeBytes())
                .position(m.getPosition())
                .build();
    }

    /**
     * Turns the document's media map into a gallery-ordered list.
     *
     * @param media media keyed by id, may be {@code null} for a product without media
     * @return the views ordered by position, never {@code null}
     */
    private List<ProductMediaView> fromDocumentMedia(Map<String, ProductMediaDocument> media) {
        if (media == null || media.isEmpty()) {
            return List.of();
        }
        return media.values().stream()
                .sorted(Comparator.comparingInt(ProductMediaDocument::getPosition))
                .map(m -> ProductMediaView.builder()
                        .id(m.getId())
                        .type(m.getType())
                        .url(storage.publicUrl(m.getObjectKey()))
                        .contentType(m.getContentType())
                        .sizeBytes(m.getSizeBytes())
                        .position(m.getPosition())
                        .build())
                .toList();
    }
}
