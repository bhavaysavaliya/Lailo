package com.lailo.application.features.product.application.query;

import com.lailo.application.common.cqrs.QueryHandler;
import com.lailo.application.features.product.domain.ProductStatus;
import com.lailo.application.features.product.infrastructure.readmodel.ProductDocument;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Serves browse and search from the MongoDB catalogue only. The read side is deliberately not allowed to
 * fall back to Postgres here: a list query must stay one indexed call, and a product missing for the ~1s
 * projection lag is acceptable for browsing (a direct product read does fall back).
 */
@Component
@RequiredArgsConstructor
public class SearchProductsHandler implements QueryHandler<SearchProductsQuery, ProductPage> {

    private final ReactiveMongoTemplate mongo;
    private final ProductViewMapper mapper;

    /**
     * Counts the matches and reads the requested page in parallel.
     *
     * @param q the query
     * @return the page, empty when nothing matches
     */
    @Override
    public Mono<ProductPage> handle(SearchProductsQuery q) {
        Criteria criteria = criteria(q);
        Query page = Query.query(criteria).with(PageRequest.of(q.page(), q.size(), sort(q.sort())));
        return mongo.count(Query.query(criteria), ProductDocument.class)
                .zipWith(mongo.find(page, ProductDocument.class).map(mapper::fromDocument).collectList())
                .map(both -> ProductPage.builder()
                        .items(both.getT2())
                        .page(q.page())
                        .size(q.size())
                        .total(both.getT1())
                        .hasNext((long) q.page() * q.size() + both.getT2().size() < both.getT1())
                        .build());
    }

    /**
     * Builds the filter: active products only, plus whichever optional filters were supplied.
     *
     * @param q the query
     * @return the Mongo criteria
     */
    private static Criteria criteria(SearchProductsQuery q) {
        List<Criteria> and = new ArrayList<>();
        and.add(Criteria.where("status").is(ProductStatus.ACTIVE.name()));
        if (q.text() != null) {
            and.add(new Criteria().orOperator(like("name", q.text()), like("brand", q.text()),
                    like("category", q.text()), like("description", q.text())));
        }
        if (q.category() != null) {
            and.add(Criteria.where("category").regex("^" + Pattern.quote(q.category()) + "$", "i"));
        }
        if (q.minPrice() != null || q.maxPrice() != null) {
            Criteria price = Criteria.where("price");
            if (q.minPrice() != null) {
                price = price.gte(q.minPrice());
            }
            if (q.maxPrice() != null) {
                price = price.lte(q.maxPrice());
            }
            and.add(price);
        }
        if (q.inStockOnly()) {
            and.add(Criteria.where("stockQuantity").gt(0));
        }
        return and.size() == 1 ? and.getFirst() : new Criteria().andOperator(and);
    }

    /**
     * Case-insensitive "contains" match. The text is quoted, so regex metacharacters typed by a user are
     * matched literally instead of changing the query.
     *
     * @param field document field
     * @param text user text
     * @return the criteria
     */
    private static Criteria like(String field, String text) {
        return Criteria.where(field).regex(Pattern.quote(text), "i");
    }

    /**
     * Translates the requested ordering into a Mongo sort.
     *
     * @param sort requested ordering
     * @return the sort specification
     */
    private static Sort sort(ProductSort sort) {
        return switch (sort) {
            case PRICE_ASC -> Sort.by(Sort.Direction.ASC, "price");
            case PRICE_DESC -> Sort.by(Sort.Direction.DESC, "price");
            case NAME_ASC -> Sort.by(Sort.Direction.ASC, "name");
            case NEWEST -> Sort.by(Sort.Direction.DESC, "createdAt");
        };
    }

    /**
     * Serves {@link SearchProductsQuery}.
     *
     * @return the query class
     */
    @Override
    public Class<SearchProductsQuery> queryType() {
        return SearchProductsQuery.class;
    }
}
