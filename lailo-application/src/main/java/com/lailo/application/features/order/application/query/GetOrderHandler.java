package com.lailo.application.features.order.application.query;

import com.lailo.application.common.cqrs.QueryHandler;
import com.lailo.application.features.order.application.OrderErrors;
import com.lailo.application.features.order.infrastructure.persistence.OrderItemRepository;
import com.lailo.application.features.order.infrastructure.persistence.OrderRepository;
import com.lailo.application.features.order.infrastructure.readmodel.OrderReadRepository;
import com.lailo.application.features.payment.domain.Payment;
import com.lailo.application.features.payment.infrastructure.persistence.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Reads one order from MongoDB, falling back to PostgreSQL.
 *
 * <p>The fallback is load-bearing here, not a nicety: the client is redirected to the order page the instant
 * checkout returns, which is well inside the projection lag. Both paths check the caller owns the order and
 * answer a foreign order with the same 404 as a missing one.</p>
 */
@Component
@RequiredArgsConstructor
public class GetOrderHandler implements QueryHandler<GetOrderQuery, OrderView> {

    private final OrderReadRepository readModel;
    private final OrderRepository orders;
    private final OrderItemRepository items;
    private final PaymentRepository payments;
    private final OrderViewMapper mapper;

    /**
     * Reads the order.
     *
     * @param q the query
     * @return the order, or {@code ORDER_NOT_FOUND}
     */
    @Override
    public Mono<OrderView> handle(GetOrderQuery q) {
        return readModel.findById(q.orderId().toString())
                .filter(doc -> q.userId().toString().equals(doc.getUserId()))
                .map(mapper::fromDocument)
                .switchIfEmpty(Mono.defer(() -> fromWriteModel(q)))
                .switchIfEmpty(Mono.error(OrderErrors.notFound(q.orderId())));
    }

    /**
     * Assembles the order from PostgreSQL while the projection catches up.
     *
     * @param q the query
     * @return the order, or empty when it does not exist or is not the caller's
     */
    private Mono<OrderView> fromWriteModel(GetOrderQuery q) {
        return orders.findById(q.orderId())
                .filter(order -> order.getUserId().equals(q.userId()))
                .flatMap(order -> items.findByOrderId(order.getId())
                        .collectList()
                        .flatMap(lines -> paymentStatus(order.getPaymentId())
                                .map(status -> mapper.fromAggregate(order, lines, status.isBlank() ? null : status))));
    }

    /**
     * Reads the status of the payment in play.
     *
     * @param paymentId the attempt's id, or {@code null} when none exists yet
     * @return the status, or an empty string when there is none - never an empty Mono, which would drop the
     *         order from the chain
     */
    private Mono<String> paymentStatus(UUID paymentId) {
        if (paymentId == null) {
            return Mono.just("");
        }
        return payments.findById(paymentId)
                .map(Payment::getStatus)
                .map(Enum::name)
                .defaultIfEmpty("");
    }

    /**
     * Serves {@link GetOrderQuery}.
     *
     * @return the query class
     */
    @Override
    public Class<GetOrderQuery> queryType() {
        return GetOrderQuery.class;
    }
}
