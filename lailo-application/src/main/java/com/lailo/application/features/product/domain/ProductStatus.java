package com.lailo.application.features.product.domain;

/** Lifecycle of a catalogue entry. Archived products stay in the write model but leave the catalogue. */
public enum ProductStatus {
    /** Visible in the catalogue and buyable. */
    ACTIVE,
    /** Withdrawn from the catalogue; kept for order history and audits. */
    ARCHIVED
}
