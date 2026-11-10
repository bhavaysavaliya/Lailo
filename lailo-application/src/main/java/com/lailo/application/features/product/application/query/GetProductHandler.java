package com.lailo.application.features.product.application.query;

import com.lailo.application.common.cqrs.QueryHandler;
import com.lailo.application.features.product.application.ProductErrors;
import com.lailo.application.features.product.domain.MediaStatus;
import com.lailo.application.features.product.infrastructure.persistence.ProductMediaRepository;
import com.lailo.application.features.product.infrastructure.persistence.ProductRepository;
import com.lailo.application.features.product.infrastructure.readmodel.ProductCatalogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Reads the MongoDB catalogue. The projection is eventually consistent (~1s behind), so a product that was
 * just created may be missing: fall back to the write model rather than return a 404 for a product that
 * exists.
 */
@Component
@RequiredArgsConstructor
public class GetProductHandler implements QueryHandler<GetProductQuery, ProductView> {

    private final ProductCatalogRepository catalog;
    private final ProductRepository products;
    private final ProductMediaRepository media;
    private final ProductViewMapper mapper;

    /**
     * Reads the product from MongoDB, falling back to PostgreSQL while the projection catches up.
     *
     * @param q the query
     * @return the product, or {@code PRODUCT_NOT_FOUND}
     */
    @Override
    public Mono<ProductView> handle(GetProductQuery q) {
        return catalog.findById(q.productId().toString())
                .map(mapper::fromDocument)
                .switchIfEmpty(Mono.defer(() -> products.findById(q.productId())
                        .flatMap(product -> media
                                .findByProductIdAndStatusOrderByPosition(product.getId(), MediaStatus.READY)
                                .collectList()
                                .map(items -> mapper.fromAggregate(product, items)))))
                .switchIfEmpty(Mono.error(ProductErrors.notFound(q.productId())));
    }

    /**
     * Serves {@link GetProductQuery}.
     *
     * @return the query class
     */
    @Override
    public Class<GetProductQuery> queryType() {
        return GetProductQuery.class;
    }
}
