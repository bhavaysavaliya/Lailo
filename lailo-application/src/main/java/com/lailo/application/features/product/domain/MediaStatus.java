package com.lailo.application.features.product.domain;

/** Upload lifecycle of one media item. Only {@code READY} items are published to the catalogue. */
public enum MediaStatus {
    /** Reserved and presigned; the bytes may not have been uploaded yet. */
    PENDING,
    /** Bytes verified in the object store; visible to shoppers. */
    READY
}
