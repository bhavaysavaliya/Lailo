package com.lailo.application.features.product.application.query;

import com.lailo.application.common.cqrs.Query;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Reads several products at once. This is how other features (cart, wishlist, checkout) enrich the product
 * ids they store: they go through the query bus instead of reaching into the catalogue's repositories, so
 * the fallback rules and the media-URL logic stay in one place.
 *
 * @param productIds ids to read; unknown ids are simply absent from the result
 */
public record GetProductsQuery(Set<UUID> productIds) implements Query<List<ProductView>> {

    /** Rejects a {@code null} id set so a caller mistake is not read as "every product". */
    public GetProductsQuery {
        if (productIds == null) {
            throw new IllegalArgumentException("productIds is required");
        }
    }
}
