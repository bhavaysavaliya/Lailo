package com.lailo.application.features.payment.application;

import com.lailo.application.common.web.ApiException;
import org.springframework.http.HttpStatus;

import java.util.Map;
import java.util.UUID;

/** Payment error codes. Messages are written for the end user; provider internals never appear. */
public final class PaymentErrors {

    /** Not instantiable: static factory holder. */
    private PaymentErrors() {
    }

    /**
     * No such payment, or it belongs to another shopper. The two are deliberately the same answer, so the
     * API cannot be used to discover that a payment id exists.
     *
     * @param paymentId the id that was looked up
     * @return 404 {@code PAYMENT_NOT_FOUND}
     */
    public static ApiException notFound(UUID paymentId) {
        return new ApiException(HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND",
                "We couldn't find that payment.", Map.of("paymentId", paymentId.toString()));
    }

    /**
     * The attempt has already been decided, so it cannot be confirmed again.
     *
     * @param status the status it is in
     * @return 409 {@code PAYMENT_NOT_PENDING}
     */
    public static ApiException notPending(String status) {
        return new ApiException(HttpStatus.CONFLICT, "PAYMENT_NOT_PENDING",
                "This payment has already been completed.", Map.of("status", status));
    }

    /**
     * The gateway declined the attempt.
     *
     * @param reason provider's reason, already user-safe
     * @return 402 {@code PAYMENT_DECLINED}
     */
    public static ApiException declined(String reason) {
        return new ApiException(HttpStatus.PAYMENT_REQUIRED, "PAYMENT_DECLINED",
                reason == null ? "Your payment was declined. Try another method." : reason);
    }

    /**
     * A callback arrived without a valid provider signature. It is answered with a 401 and nothing else: an
     * unverified caller learns nothing about whether the payment exists.
     *
     * @return 401 {@code WEBHOOK_SIGNATURE_INVALID}
     */
    public static ApiException webhookSignatureInvalid() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "WEBHOOK_SIGNATURE_INVALID", "Invalid signature.");
    }

    /**
     * Only an authorised payment can be given back.
     *
     * @param status the status it is in
     * @return 409 {@code PAYMENT_NOT_REFUNDABLE}
     */
    public static ApiException notRefundable(String status) {
        return new ApiException(HttpStatus.CONFLICT, "PAYMENT_NOT_REFUNDABLE",
                "This payment can't be refunded.", Map.of("status", status));
    }
}
