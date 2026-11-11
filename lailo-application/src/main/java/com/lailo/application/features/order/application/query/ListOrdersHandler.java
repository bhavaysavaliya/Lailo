package com.lailo.application.features.order.application.query;

import com.lailo.application.common.cqrs.QueryHandler;
import com.lailo.application.features.order.domain.OrderBucket;
import com.lailo.application.features.order.infrastructure.readmodel.OrderDocument;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Serves "my orders" from MongoDB, split into the tabs the shopper asked for: what has shipped and what is
 * still to be shipped.
 *
 * <p>The filter is a single equality match on the stored {@code bucket}, because the projector already
 * worked out which tab each order belongs to. The page and the four counts are fetched concurrently, so the
 * tab labels cost latency only once.</p>
 *
 * <p>Unlike a single-order read, this does not fall back to PostgreSQL: a list must stay one indexed query,
 * and a just-placed order missing from the list for ~1s is covered by the client being sent to that order's
 * own page, which does fall back.</p>
 */
@Component
@RequiredArgsConstructor
public class ListOrdersHandler implements QueryHandler<ListOrdersQuery, OrderPage> {

    private final ReactiveMongoTemplate mongo;
    private final OrderViewMapper mapper;

    /**
     * Reads one page of the requested tab, with the counts for every tab.
     *
     * @param q the query
     * @return the page, empty when the shopper has no orders in that tab
     */
    @Override
    public Mono<OrderPage> handle(ListOrdersQuery q) {
        Criteria criteria = criteria(q.userId(), q.bucket());
        Query page = Query.query(criteria)
                .with(PageRequest.of(q.page(), q.size(), Sort.by(Sort.Direction.DESC, "placedAt")));

        Mono<Long> total = mongo.count(Query.query(criteria), OrderDocument.class);
        Mono<java.util.List<OrderView>> items = mongo.find(page, OrderDocument.class)
                .map(mapper::fromDocument)
                .collectList();

        return Mono.zip(total, items, counts(q.userId()))
                .map(result -> {
                    long matching = result.getT1();
                    java.util.List<OrderView> found = result.getT2();
                    return OrderPage.builder()
                            .items(found)
                            .bucket(q.bucket().name())
                            .page(q.page())
                            .size(q.size())
                            .total(matching)
                            .hasNext((long) q.page() * q.size() + found.size() < matching)
                            .counts(result.getT3())
                            .build();
                });
    }

    /**
     * Counts the three tabs concurrently.
     *
     * @param userId whose orders to count
     * @return the counts
     */
    private Mono<OrderCounts> counts(UUID userId) {
        Mono<Long> toBeShipped = countBucket(userId, OrderBucket.TO_BE_SHIPPED);
        Mono<Long> shipped = countBucket(userId, OrderBucket.SHIPPED);
        Mono<Long> cancelled = countBucket(userId, OrderBucket.CANCELLED);
        return Mono.zip(toBeShipped, shipped, cancelled)
                .map(counts -> new OrderCounts(counts.getT1(), counts.getT2(), counts.getT3()));
    }

    /**
     * Counts one tab.
     *
     * @param userId whose orders to count
     * @param bucket the tab
     * @return the count
     */
    private Mono<Long> countBucket(UUID userId, OrderBucket bucket) {
        return mongo.count(Query.query(criteria(userId, bucket)), OrderDocument.class);
    }

    /**
     * Builds the filter: always the caller's own orders, narrowed to one tab unless {@code ALL} was asked
     * for.
     *
     * @param userId whose orders to list
     * @param bucket the tab
     * @return the Mongo criteria
     */
    private static Criteria criteria(UUID userId, OrderBucket bucket) {
        Criteria mine = Criteria.where("userId").is(userId.toString());
        return bucket == OrderBucket.ALL ? mine : mine.and("bucket").is(bucket.name());
    }

    /**
     * Serves {@link ListOrdersQuery}.
     *
     * @return the query class
     */
    @Override
    public Class<ListOrdersQuery> queryType() {
        return ListOrdersQuery.class;
    }
}
