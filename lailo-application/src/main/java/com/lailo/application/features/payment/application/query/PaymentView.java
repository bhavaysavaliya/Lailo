package com.lailo.application.features.payment.application.query;

import com.lailo.application.features.payment.domain.Payment;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A payment attempt as the API returns it.
 *
 * <p>{@code checkoutUrl} is only filled in on the response that created the attempt - it is where the client
 * sends the shopper, and it is never stored, so a later read of the same payment does not hand out a stale
 * gateway link.</p>
 *
 * @param id the attempt's id
 * @param orderId order being paid for
 * @param amount amount to charge
 * @param currency ISO 4217 code
 * @param method how the shopper chose to pay
 * @param status where the attempt stands
 * @param provider gateway name
 * @param providerReference gateway's reference, which the client needs to complete the payment
 * @param checkoutUrl where to send the shopper, or {@code null}
 * @param failureReason why it was declined, or {@code null}
 * @param createdAt when the attempt was created
 * @param authorizedAt when the funds were guaranteed, or {@code null}
 */
@Builder
public record PaymentView(String id, String orderId, BigDecimal amount, String currency, String method,
                          String status, String provider, String providerReference, String checkoutUrl,
                          String failureReason, Instant createdAt, Instant authorizedAt) {

    /**
     * Maps the stored attempt, without a checkout link.
     *
     * @param payment the attempt as stored
     * @return the view
     */
    public static PaymentView of(Payment payment) {
        return of(payment, null);
    }

    /**
     * Maps the stored attempt and attaches the gateway link the client should follow.
     *
     * @param payment the attempt as stored
     * @param checkoutUrl where to send the shopper, or {@code null}
     * @return the view
     */
    public static PaymentView of(Payment payment, String checkoutUrl) {
        return PaymentView.builder()
                .id(payment.getId().toString())
                .orderId(payment.getOrderId().toString())
                .amount(payment.getAmount())
                .currency(payment.getCurrency())
                .method(payment.getMethod().name())
                .status(payment.getStatus().name())
                .provider(payment.getProvider())
                .providerReference(payment.getProviderReference())
                .checkoutUrl(checkoutUrl)
                .failureReason(payment.getFailureReason())
                .createdAt(payment.getCreatedAt())
                .authorizedAt(payment.getAuthorizedAt())
                .build();
    }
}
