package com.lailo.application.common.saga;

/** Where a saga instance stands overall, independently of which step it is on. */
public enum SagaStatus {
    /** Moving forward through its steps. */
    RUNNING,
    /** Every step succeeded. */
    COMPLETED,
    /** A step failed; compensating actions are being applied in reverse. */
    COMPENSATING,
    /** Compensation finished: the business outcome was undone, not committed. */
    COMPENSATED,
    /** Compensation itself failed; needs an operator. */
    FAILED
}
