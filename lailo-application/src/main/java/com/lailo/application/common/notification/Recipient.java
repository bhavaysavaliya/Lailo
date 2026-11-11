package com.lailo.application.common.notification;

import lombok.Builder;

/**
 * Who a notification is addressed to. A user may be reachable by email, by phone, or by both: the
 * dispatcher simply skips a channel whose destination is missing, so callers never branch on it.
 *
 * @param userId the account the notification belongs to, used for correlation in logs
 * @param name display name used in greetings, or {@code null} when unknown
 * @param email email address, or {@code null} when the account has none
 * @param phone mobile number in E.164, or {@code null} when the account has none
 */
@Builder
public record Recipient(String userId, String name, String email, String phone) {

    /**
     * Whether an email can be delivered to this recipient.
     *
     * @return {@code true} when an email address is present
     */
    public boolean hasEmail() {
        return email != null && !email.isBlank();
    }

    /**
     * Whether a text message can be delivered to this recipient.
     *
     * @return {@code true} when a phone number is present
     */
    public boolean hasPhone() {
        return phone != null && !phone.isBlank();
    }

    /**
     * Masks both destinations, so the logging aspect can print a recipient without leaking contact details.
     *
     * @return a log-safe representation
     */
    @Override
    public String toString() {
        return "Recipient[userId=" + userId + ", email=" + Masks.mask(email) + ", phone=" + Masks.mask(phone) + "]";
    }
}
