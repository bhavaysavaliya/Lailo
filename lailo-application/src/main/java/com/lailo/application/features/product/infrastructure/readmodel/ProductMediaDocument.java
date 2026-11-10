package com.lailo.application.features.product.infrastructure.readmodel;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One media item inside a catalogue document. Only the object key is stored, never a URL: the public base
 * URL (or CDN host) is applied when the view is built, so changing it takes effect immediately with no
 * reprojection.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductMediaDocument {
    private String id;
    private String type;
    private String objectKey;
    private String contentType;
    private long sizeBytes;
    private int position;
}
