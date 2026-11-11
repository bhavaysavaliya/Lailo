package com.lailo.application.features.payment.domain;

/**
 * How a shopper pays. Lives in the payment feature because it decides which gateway flow applies, and is
 * referenced by orders, which only record what the shopper chose.
 */
public enum PaymentMethod {
    /** Credit or debit card, authorised by the gateway. */
    CARD,
    /** UPI collect / intent, the default in India. */
    UPI,
    /** Internet banking redirect. */
    NETBANKING,
    /** Prepaid wallet. */
    WALLET,
    /** Cash on delivery: nothing is captured online, so it is authorised the moment the order is placed. */
    COD;

    /**
     * Whether this method needs the shopper to complete something at the gateway before the order can be
     * confirmed.
     *
     * @return {@code true} for every online method, {@code false} for cash on delivery
     */
    public boolean requiresOnlineAuthorization() {
        return this != COD;
    }
}
