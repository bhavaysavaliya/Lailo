package com.lailo.application.common.notification;

import lombok.Builder;

/**
 * One transactional email, ready to hand to a provider. Content is rendered by the feature that raises the
 * notification, so this type stays provider-agnostic: swapping SES for Postmark changes one bean.
 *
 * @param to recipient address
 * @param subject subject line
 * @param textBody plain-text body, always present, as some clients render nothing else
 * @param htmlBody HTML body, or {@code null} to send text only
 */
@Builder
public record EmailMessage(String to, String subject, String textBody, String htmlBody) {

    /**
     * Rejects a message no provider could accept, so the failure surfaces at the call site rather than as a
     * 4xx from the gateway.
     */
    public EmailMessage {
        if (to == null || to.isBlank()) {
            throw new IllegalArgumentException("to is required");
        }
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("subject is required");
        }
        if (textBody == null || textBody.isBlank()) {
            throw new IllegalArgumentException("textBody is required");
        }
    }

    /**
     * Masks the address and omits the bodies, which routinely carry order details.
     *
     * @return a log-safe representation
     */
    @Override
    public String toString() {
        return "EmailMessage[to=" + Masks.mask(to) + ", subject=" + subject + "]";
    }
}
