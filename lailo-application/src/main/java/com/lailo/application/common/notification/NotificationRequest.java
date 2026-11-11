package com.lailo.application.common.notification;

import lombok.Builder;

/**
 * A rendered notification waiting to be delivered on every channel the recipient can be reached on.
 *
 * <p>There is deliberately no channel list: a channel is used when the recipient has a destination for it
 * and this request carries content for it. That keeps call sites free of "if the user has a phone" logic
 * while still letting a caller send email only (leave {@code smsBody} null) or SMS only.</p>
 *
 * @param dedupeKey idempotency key, normally the id of the event that triggered the notification; two
 *        requests sharing it deliver once per channel
 * @param recipient who to reach
 * @param subject email subject line; required when {@code emailBody} is set
 * @param emailBody plain-text email body, or {@code null} to skip email
 * @param htmlBody HTML email body, or {@code null} to send text only
 * @param smsBody short text body, or {@code null} to skip SMS
 */
@Builder
public record NotificationRequest(String dedupeKey, Recipient recipient, String subject,
                                  String emailBody, String htmlBody, String smsBody) {

    /**
     * Enforces what the dispatcher relies on: an idempotency key, a recipient, and a subject whenever there
     * is an email body.
     */
    public NotificationRequest {
        if (dedupeKey == null || dedupeKey.isBlank()) {
            throw new IllegalArgumentException("dedupeKey is required");
        }
        if (recipient == null) {
            throw new IllegalArgumentException("recipient is required");
        }
        if (emailBody != null && (subject == null || subject.isBlank())) {
            throw new IllegalArgumentException("subject is required when an email body is given");
        }
    }

    /**
     * Whether this request should produce an email.
     *
     * @return {@code true} when there is a body to send and an address to send it to
     */
    public boolean wantsEmail() {
        return emailBody != null && recipient.hasEmail();
    }

    /**
     * Whether this request should produce a text message.
     *
     * @return {@code true} when there is a body to send and a number to send it to
     */
    public boolean wantsSms() {
        return smsBody != null && recipient.hasPhone();
    }

    /**
     * Omits the bodies, which carry order and account details.
     *
     * @return a log-safe representation
     */
    @Override
    public String toString() {
        return "NotificationRequest[dedupeKey=" + dedupeKey + ", recipient=" + recipient
                + ", email=" + wantsEmail() + ", sms=" + wantsSms() + "]";
    }
}
