package com.lailo.application.features.payment.domain.event;

import com.lailo.application.features.payment.domain.Payment;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Integration events for payments, published through the outbox.
 *
 * <p>The checkout saga is the main consumer: {@code PaymentAuthorized} is what lets it confirm the order and
 * {@code PaymentFailed} is what starts compensation. The events are keyed by order id, not payment id, so
 * every event about one order - including a retry's - stays ordered in the same partition.</p>
 */
public final class PaymentEvents {

    public static final String TOPIC = "lailo.payment.payment-events.v1";
    public static final String AGGREGATE = "Payment";

    public static final String PAYMENT_INITIATED = "PaymentInitiated";
    public static final String PAYMENT_AUTHORIZED = "PaymentAuthorized";
    public static final String PAYMENT_FAILED = "PaymentFailed";
    public static final String PAYMENT_REFUNDED = "PaymentRefunded";

    /** Not instantiable: constants and event types only. */
    private PaymentEvents() {
    }

    /**
     * An attempt was created and the shopper can now be sent to the gateway.
     *
     * @param eventId unique event id
     * @param paymentId the attempt's id
     * @param orderId order being paid for
     * @param userId the shopper
     * @param amount amount to charge
     * @param currency ISO 4217 code
     * @param method how the shopper chose to pay
     * @param provider gateway name
     * @param occurredAt creation time
     */
    @Builder
    public record PaymentInitiated(String eventId, String paymentId, String orderId, String userId,
                                   BigDecimal amount, String currency, String method, String provider,
                                   Instant occurredAt) {

        /**
         * Builds the event from the saved attempt.
         *
         * @param payment the attempt as stored
         * @return the event, with a fresh event id
         */
        public static PaymentInitiated of(Payment payment) {
            return PaymentInitiated.builder()
                    .eventId(UUID.randomUUID().toString())
                    .paymentId(payment.getId().toString())
                    .orderId(payment.getOrderId().toString())
                    .userId(payment.getUserId().toString())
                    .amount(payment.getAmount())
                    .currency(payment.getCurrency())
                    .method(payment.getMethod().name())
                    .provider(payment.getProvider())
                    .occurredAt(payment.getCreatedAt())
                    .build();
        }
    }

    /**
     * The gateway guaranteed the funds.
     *
     * @param eventId unique event id
     * @param paymentId the attempt's id
     * @param orderId order that is now paid for
     * @param userId the shopper
     * @param amount amount authorised
     * @param currency ISO 4217 code
     * @param method how the shopper paid
     * @param occurredAt authorisation time
     */
    @Builder
    public record PaymentAuthorized(String eventId, String paymentId, String orderId, String userId,
                                    BigDecimal amount, String currency, String method, Instant occurredAt) {

        /**
         * Builds the event from the authorised attempt.
         *
         * @param payment the attempt as stored
         * @return the event, with a fresh event id
         */
        public static PaymentAuthorized of(Payment payment) {
            return PaymentAuthorized.builder()
                    .eventId(UUID.randomUUID().toString())
                    .paymentId(payment.getId().toString())
                    .orderId(payment.getOrderId().toString())
                    .userId(payment.getUserId().toString())
                    .amount(payment.getAmount())
                    .currency(payment.getCurrency())
                    .method(payment.getMethod().name())
                    .occurredAt(payment.getAuthorizedAt())
                    .build();
        }
    }

    /**
     * The attempt was declined, abandoned or timed out.
     *
     * @param eventId unique event id
     * @param paymentId the attempt's id
     * @param orderId order that is not paid for
     * @param userId the shopper
     * @param reason provider's reason, safe to show
     * @param occurredAt failure time
     */
    public record PaymentFailed(String eventId, String paymentId, String orderId, String userId, String reason,
                                Instant occurredAt) {

        /**
         * Builds the event from the failed attempt.
         *
         * @param payment the attempt as stored
         * @return the event, with a fresh event id
         */
        public static PaymentFailed of(Payment payment) {
            return new PaymentFailed(UUID.randomUUID().toString(), payment.getId().toString(),
                    payment.getOrderId().toString(), payment.getUserId().toString(),
                    payment.getFailureReason(), payment.getUpdatedAt());
        }
    }

    /**
     * Authorised money was given back, because the order was cancelled after payment.
     *
     * @param eventId unique event id
     * @param paymentId the attempt's id
     * @param orderId order that was refunded
     * @param userId the shopper
     * @param amount amount returned
     * @param currency ISO 4217 code
     * @param occurredAt refund time
     */
    @Builder
    public record PaymentRefunded(String eventId, String paymentId, String orderId, String userId,
                                  BigDecimal amount, String currency, Instant occurredAt) {

        /**
         * Builds the event from the refunded attempt.
         *
         * @param payment the attempt as stored
         * @return the event, with a fresh event id
         */
        public static PaymentRefunded of(Payment payment) {
            return PaymentRefunded.builder()
                    .eventId(UUID.randomUUID().toString())
                    .paymentId(payment.getId().toString())
                    .orderId(payment.getOrderId().toString())
                    .userId(payment.getUserId().toString())
                    .amount(payment.getAmount())
                    .currency(payment.getCurrency())
                    .occurredAt(payment.getRefundedAt())
                    .build();
        }
    }
}
