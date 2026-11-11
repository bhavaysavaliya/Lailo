package com.lailo.application.features.payment.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Payment settings. The secret is required and has a minimum length, so a deployment cannot accidentally
 * accept unsigned webhooks.
 *
 * @param provider which gateway adapter to wire; {@code simulated} is the only one built so far
 * @param webhookSecret shared secret the provider signs callbacks with, at least 32 characters
 * @param intentTtl how long a payment intent stays usable
 * @param simulatedCheckoutUrl page the simulated gateway pretends to be, used only in development
 */
@Validated
@ConfigurationProperties(prefix = "lailo.payment")
public record PaymentProperties(
        @DefaultValue("simulated") @Pattern(regexp = "simulated") String provider,
        @NotBlank @Size(min = 32, message = "must be at least 32 characters") String webhookSecret,
        @DefaultValue("15m") Duration intentTtl,
        @DefaultValue("http://localhost:3000/checkout/simulated") String simulatedCheckoutUrl) {
}
