package com.lailo.application.features.payment.application.query;

import com.lailo.application.common.cqrs.Query;

import java.util.UUID;

/**
 * Reads one payment attempt.
 *
 * @param paymentId the attempt to read
 * @param userId the caller, so an attempt can only be read by the shopper it belongs to
 */
public record GetPaymentQuery(UUID paymentId, UUID userId) implements Query<PaymentView> {
}
