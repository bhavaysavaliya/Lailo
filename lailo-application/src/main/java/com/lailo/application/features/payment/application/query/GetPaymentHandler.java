package com.lailo.application.features.payment.application.query;

import com.lailo.application.common.cqrs.QueryHandler;
import com.lailo.application.features.payment.application.PaymentErrors;
import com.lailo.application.features.payment.infrastructure.persistence.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Reads a payment attempt from the write model.
 *
 * <p>There is no read-model projection for payments on purpose: this is money, it is read rarely and always
 * by id, and "eventually consistent" is the wrong property for the screen that tells someone whether they
 * have paid.</p>
 */
@Component
@RequiredArgsConstructor
public class GetPaymentHandler implements QueryHandler<GetPaymentQuery, PaymentView> {

    private final PaymentRepository payments;

    /**
     * Reads the attempt, refusing one that belongs to another shopper with the same 404 as a missing id.
     *
     * @param q the query
     * @return the attempt, or {@code PAYMENT_NOT_FOUND}
     */
    @Override
    public Mono<PaymentView> handle(GetPaymentQuery q) {
        return payments.findById(q.paymentId())
                .filter(payment -> payment.getUserId().equals(q.userId()))
                .map(PaymentView::of)
                .switchIfEmpty(Mono.error(PaymentErrors.notFound(q.paymentId())));
    }

    /**
     * Serves {@link GetPaymentQuery}.
     *
     * @return the query class
     */
    @Override
    public Class<GetPaymentQuery> queryType() {
        return GetPaymentQuery.class;
    }
}
