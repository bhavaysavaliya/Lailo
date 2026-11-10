package com.lailo.application.features.product.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Catalogue media policy. Separate from {@code lailo.storage} on purpose: where bytes live is
 * infrastructure, how big a product photo may be is a product decision.
 *
 * @param image limits for still images
 * @param video limits for video clips
 * @param maxPerProduct how many media items one product may have, counting pending uploads
 */
@Validated
@ConfigurationProperties(prefix = "lailo.product.media")
public record MediaProperties(@Valid Limits image, @Valid Limits video,
                              @DefaultValue("12") @Min(1) int maxPerProduct) {

    /**
     * Size limits for one media kind.
     *
     * @param maxBytes largest accepted upload
     */
    public record Limits(@DefaultValue("10485760") @Min(1) long maxBytes) {
    }
}
