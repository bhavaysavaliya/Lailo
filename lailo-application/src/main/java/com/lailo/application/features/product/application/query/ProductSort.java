package com.lailo.application.features.product.application.query;

/** Ordering options a catalogue search may request. */
public enum ProductSort {
    /** Most recently added first; the default. */
    NEWEST,
    /** Cheapest first. */
    PRICE_ASC,
    /** Most expensive first. */
    PRICE_DESC,
    /** Alphabetical by name. */
    NAME_ASC
}
