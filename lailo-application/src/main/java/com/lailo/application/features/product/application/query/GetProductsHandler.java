package com.lailo.application.features.product.application.query;

import com.lailo.application.common.cqrs.QueryHandler;
import com.lailo.application.features.product.domain.MediaStatus;
import com.lailo.application.features.product.infrastructure.persistence.ProductMediaRepository;
import com.lailo.application.features.product.infrastructure.persistence.ProductRepository;
import com.lailo.application.features.product.infrastructure.readmodel.ProductCatalogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Bulk read of the catalogue, with the same read-your-write guarantee as the single-product read: whatever
 * MongoDB has not projected yet is fetched from the write model, so a product added to a cart seconds after
 * it was created still renders.
 *
 * <p>Archived products are returned too - a cart or an order must be able to show "no longer available"
 * rather than silently drop a line.</p>
 */
@Component
@RequiredArgsConstructor
public class GetProductsHandler implements QueryHandler<GetProductsQuery, List<ProductView>> {

    private final ProductCatalogRepository catalog;
    private final ProductRepository products;
    private final ProductMediaRepository media;
    private final ProductViewMapper mapper;

    /**
     * Reads the requested products from the catalogue, then tops the result up from PostgreSQL.
     *
     * @param q the query
     * @return the products that exist, in no particular order
     */
    @Override
    public Mono<List<ProductView>> handle(GetProductsQuery q) {
        if (q.productIds().isEmpty()) {
            return Mono.just(List.of());
        }
        List<String> ids = q.productIds().stream().map(UUID::toString).toList();
        return catalog.findAllById(ids)
                .map(mapper::fromDocument)
                .collectList()
                .flatMap(projected -> fillGaps(q.productIds(), projected));
    }

    /**
     * Fetches the ids MongoDB did not return from the write model.
     *
     * @param requested every id that was asked for
     * @param projected what the catalogue already had
     * @return the combined list
     */
    private Mono<List<ProductView>> fillGaps(Set<UUID> requested, List<ProductView> projected) {
        Set<UUID> missing = new HashSet<>(requested);
        projected.forEach(view -> missing.remove(UUID.fromString(view.id())));
        if (missing.isEmpty()) {
            return Mono.just(projected);
        }
        return products.findAllById(missing)
                .flatMap(product -> media
                        .findByProductIdAndStatusOrderByPosition(product.getId(), MediaStatus.READY)
                        .collectList()
                        .map(items -> mapper.fromAggregate(product, items)))
                .collectList()
                .map(fromWriteModel -> {
                    List<ProductView> all = new ArrayList<>(projected);
                    all.addAll(fromWriteModel);
                    return List.copyOf(all);
                });
    }

    /**
     * Serves {@link GetProductsQuery}.
     *
     * @return the query class
     */
    @Override
    public Class<GetProductsQuery> queryType() {
        return GetProductsQuery.class;
    }
}
