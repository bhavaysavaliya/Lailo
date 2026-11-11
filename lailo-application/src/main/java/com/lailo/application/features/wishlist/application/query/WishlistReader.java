package com.lailo.application.features.wishlist.application.query;

import com.lailo.application.common.cqrs.QueryBus;
import com.lailo.application.features.product.application.query.GetProductsQuery;
import com.lailo.application.features.product.application.query.ProductMediaView;
import com.lailo.application.features.product.application.query.ProductView;
import com.lailo.application.features.product.domain.ProductStatus;
import com.lailo.application.features.wishlist.domain.WishlistItem;
import com.lailo.application.features.wishlist.infrastructure.persistence.WishlistItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Builds a {@link WishlistView} from the stored entries plus one bulk catalogue read, for the same reason
 * {@code CartReader} does: a wishlist is only useful if its prices and stock are current, so there is nothing
 * worth projecting into a read model.
 */
@Component
@RequiredArgsConstructor
public class WishlistReader {

    private final WishlistItemRepository wishlistItems;
    private final QueryBus queryBus;

    /**
     * Reads and enriches a wishlist.
     *
     * @param userId whose wishlist to read
     * @return the wishlist, empty rather than absent when nothing is saved
     */
    public Mono<WishlistView> read(UUID userId) {
        return wishlistItems.findByUserIdOrderByCreatedAtDesc(userId)
                .collectList()
                .flatMap(entries -> entries.isEmpty()
                        ? Mono.just(WishlistView.builder().userId(userId.toString()).items(List.of())
                        .itemCount(0).build())
                        : enrich(userId, entries));
    }

    /**
     * Joins the saved entries with catalogue detail.
     *
     * @param userId whose wishlist this is
     * @param entries the stored entries
     * @return the assembled view
     */
    private Mono<WishlistView> enrich(UUID userId, List<WishlistItem> entries) {
        Set<UUID> productIds = entries.stream().map(WishlistItem::getProductId).collect(Collectors.toSet());
        return queryBus.ask(new GetProductsQuery(productIds))
                .map(products -> {
                    Map<String, ProductView> byId = products.stream()
                            .collect(Collectors.toMap(ProductView::id, Function.identity()));
                    List<WishlistItemView> items = entries.stream()
                            .map(entry -> toView(entry, byId.get(entry.getProductId().toString())))
                            .toList();
                    return WishlistView.builder()
                            .userId(userId.toString())
                            .items(items)
                            .itemCount(items.size())
                            .build();
                });
    }

    /**
     * Maps one entry. A product that has left the catalogue stays on the list marked unavailable, because the
     * shopper chose to remember it and should be the one to drop it.
     *
     * @param entry the stored entry
     * @param product the catalogue entry, or {@code null} when it no longer exists
     * @return the item view
     */
    private static WishlistItemView toView(WishlistItem entry, ProductView product) {
        boolean known = product != null;
        boolean active = known && ProductStatus.ACTIVE.name().equals(product.status());
        return WishlistItemView.builder()
                .productId(entry.getProductId().toString())
                .name(known ? product.name() : "Unavailable product")
                .slug(known ? product.slug() : null)
                .imageUrl(firstImage(product))
                .price(known ? product.price() : null)
                .currency(known ? product.currency() : null)
                .inStock(active && product.stockQuantity() > 0)
                .available(active)
                .note(entry.getNote())
                .savedAt(entry.getCreatedAt())
                .build();
    }

    /**
     * Picks the image a wishlist card should show.
     *
     * @param product the catalogue entry, or {@code null}
     * @return the first gallery image URL, or {@code null}
     */
    private static String firstImage(ProductView product) {
        if (product == null || product.media() == null || product.media().isEmpty()) {
            return null;
        }
        return product.media().stream()
                .filter(m -> "IMAGE".equals(m.type()))
                .map(ProductMediaView::url)
                .findFirst()
                .orElse(null);
    }
}
