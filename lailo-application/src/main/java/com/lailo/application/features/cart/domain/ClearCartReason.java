package com.lailo.application.features.cart.domain;

/** Why a cart was emptied. Carried on {@code CartCleared}, which analytics needs to tell the two apart. */
public enum ClearCartReason {
    /** The cart became an order. */
    CHECKED_OUT,
    /** The shopper emptied it themselves. */
    CLEARED_BY_USER
}
