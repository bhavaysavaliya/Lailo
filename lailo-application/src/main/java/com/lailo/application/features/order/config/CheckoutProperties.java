package com.lailo.application.features.order.config;

import jakarta.validation.constraints.DecimalMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;

/**
 * Checkout policy. These are commercial decisions, not infrastructure, so they are configuration rather than
 * constants: marketing changes the free-delivery threshold far more often than anyone changes the code.
 *
 * @param shippingFee delivery charge applied below the threshold
 * @param freeShippingThreshold basket value at or above which delivery is free
 * @param codEnabled whether cash on delivery may be chosen
 */
@Validated
@ConfigurationProperties(prefix = "lailo.checkout")
public record CheckoutProperties(
        @DefaultValue("49.00") @DecimalMin("0.00") BigDecimal shippingFee,
        @DefaultValue("499.00") @DecimalMin("0.00") BigDecimal freeShippingThreshold,
        @DefaultValue("true") boolean codEnabled) {

    /**
     * The delivery charge for a basket.
     *
     * @param itemsTotal sum of the lines
     * @return the fee, or zero when the basket qualifies for free delivery
     */
    public BigDecimal shippingFeeFor(BigDecimal itemsTotal) {
        return itemsTotal.compareTo(freeShippingThreshold) >= 0 ? BigDecimal.ZERO : shippingFee;
    }
}
