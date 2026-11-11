package com.lailo.application.features.payment.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.With;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One payment attempt (Postgres write model). It records Lailo's view of the attempt and the gateway's
 * reference for it; no card number, UPI handle or CVV ever reaches this table or this process.
 *
 * <p>A declined attempt is kept rather than overwritten, and a retry creates a new row - which is what makes
 * reconciliation against the provider's settlement file possible.</p>
 */
@Table("payments")
@Getter
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Payment {

    @Id
    private UUID id;
    private UUID orderId;
    private UUID userId;
    private BigDecimal amount;
    private String currency;
    private PaymentMethod method;
    private PaymentStatus status;
    /** Which gateway handled it, so a provider migration leaves old rows interpretable. */
    private String provider;
    private String providerReference;
    private String failureReason;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant authorizedAt;
    private Instant refundedAt;
    /** Optimistic locking; null marks a new entity for Spring Data. */
    @Version
    private Long version;

    /**
     * Creates a pending attempt for an order.
     *
     * @param id new payment id
     * @param orderId order being paid for
     * @param userId the shopper
     * @param amount amount to charge
     * @param currency ISO 4217 code
     * @param method how the shopper chose to pay
     * @param provider gateway name
     * @param now creation time
     * @return an unsaved payment with a {@code null} version, so Spring Data inserts it
     */
    public static Payment initiate(UUID id, UUID orderId, UUID userId, BigDecimal amount, String currency,
                                   PaymentMethod method, String provider, Instant now) {
        return Payment.builder()
                .id(id)
                .orderId(orderId)
                .userId(userId)
                .amount(amount)
                .currency(currency)
                .method(method)
                .status(PaymentStatus.PENDING)
                .provider(provider)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    /**
     * Returns a copy the gateway has guaranteed the funds for.
     *
     * @param reference the gateway's reference for the authorisation
     * @param now authorisation time
     * @return the authorised payment
     */
    public Payment authorize(String reference, Instant now) {
        return withStatus(PaymentStatus.AUTHORIZED)
                .withProviderReference(reference)
                .withFailureReason(null)
                .withAuthorizedAt(now)
                .withUpdatedAt(now);
    }

    /**
     * Returns a copy marked as declined, keeping the reason for support.
     *
     * @param reason provider's reason, truncated to the column width
     * @param now failure time
     * @return the failed payment
     */
    public Payment fail(String reason, Instant now) {
        String safe = reason == null ? "declined" : reason.substring(0, Math.min(reason.length(), 200));
        return withStatus(PaymentStatus.FAILED).withFailureReason(safe).withUpdatedAt(now);
    }

    /**
     * Returns a copy whose authorised amount has been given back.
     *
     * @param now refund time
     * @return the refunded payment
     */
    public Payment refund(Instant now) {
        return withStatus(PaymentStatus.REFUNDED).withRefundedAt(now).withUpdatedAt(now);
    }

    /**
     * Whether the money is secured and the order may be confirmed.
     *
     * @return {@code true} when the status is {@code AUTHORIZED}
     */
    public boolean isAuthorized() {
        return status == PaymentStatus.AUTHORIZED;
    }

    /**
     * Whether this attempt is still open to a confirmation.
     *
     * @return {@code true} when the status is {@code PENDING}
     */
    public boolean isPending() {
        return status == PaymentStatus.PENDING;
    }
}
