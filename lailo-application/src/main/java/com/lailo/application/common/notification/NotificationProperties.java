package com.lailo.application.common.notification;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Settings every notification template needs. Shared, because an order email and an OTP email must carry the
 * same brand, sender and links.
 *
 * @param brandName name used in subjects and signatures
 * @param fromEmail envelope sender for transactional email
 * @param fromName display name for the sender
 * @param webBaseUrl public base URL of the web app, used to build links such as order tracking
 * @param supportEmail address users are told to contact
 */
@Validated
@ConfigurationProperties(prefix = "lailo.notification")
public record NotificationProperties(
        @DefaultValue("Lailo") @NotBlank String brandName,
        @DefaultValue("no-reply@lailo.in") @NotBlank String fromEmail,
        @DefaultValue("Lailo") @NotBlank String fromName,
        @DefaultValue("http://localhost:3000") @NotBlank String webBaseUrl,
        @DefaultValue("support@lailo.in") @NotBlank String supportEmail) {

    /**
     * Builds a link into the web app.
     *
     * @param path path starting with a slash, e.g. {@code /orders/123}
     * @return the absolute URL
     */
    public String link(String path) {
        String base = webBaseUrl.endsWith("/") ? webBaseUrl.substring(0, webBaseUrl.length() - 1) : webBaseUrl;
        return base + (path.startsWith("/") ? path : "/" + path);
    }
}
