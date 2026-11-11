package com.lailo.application.features.cart.application.query;

import com.lailo.application.common.cqrs.QueryHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Serves the cart read. All of the work is in {@link CartReader}, which the write side reuses. */
@Component
@RequiredArgsConstructor
public class GetCartHandler implements QueryHandler<GetCartQuery, CartView> {

    private final CartReader reader;

    /**
     * Reads the shopper's cart.
     *
     * @param q the query
     * @return the cart, empty when nothing has been added
     */
    @Override
    public Mono<CartView> handle(GetCartQuery q) {
        return reader.read(q.userId());
    }

    /**
     * Serves {@link GetCartQuery}.
     *
     * @return the query class
     */
    @Override
    public Class<GetCartQuery> queryType() {
        return GetCartQuery.class;
    }
}
