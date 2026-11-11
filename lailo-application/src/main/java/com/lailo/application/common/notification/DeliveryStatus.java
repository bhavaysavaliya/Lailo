package com.lailo.application.common.notification;

/** Lifecycle of one attempted delivery. */
public enum DeliveryStatus {
    /** Claimed by a dispatcher and currently being sent. */
    PENDING,
    /** The provider accepted the message; it is never sent again. */
    SENT,
    /** The provider rejected or timed out; a redelivery of the source event retries it. */
    FAILED
}
