package com.lailo.application.features.wishlist.application.query;

import com.lailo.application.common.cqrs.QueryHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Serves the wishlist read; the work lives in {@link WishlistReader}, which the write side reuses. */
@Component
@RequiredArgsConstructor
public class GetWishlistHandler implements QueryHandler<GetWishlistQuery, WishlistView> {

    private final WishlistReader reader;

    /**
     * Reads the shopper's wishlist.
     *
     * @param q the query
     * @return the wishlist, empty when nothing is saved
     */
    @Override
    public Mono<WishlistView> handle(GetWishlistQuery q) {
        return reader.read(q.userId());
    }

    /**
     * Serves {@link GetWishlistQuery}.
     *
     * @return the query class
     */
    @Override
    public Class<GetWishlistQuery> queryType() {
        return GetWishlistQuery.class;
    }
}
