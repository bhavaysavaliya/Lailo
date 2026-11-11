package com.lailo.application.features.auth.application.query;

import com.lailo.application.common.notification.Recipient;

/**
 * How to reach a user. Exposed as a query result so any feature can address a notification without reading
 * the auth tables itself - the whole point of keeping the notification ports common.
 *
 * @param userId the account
 * @param email verified email, or {@code null}
 * @param phone verified mobile in E.164, or {@code null}
 */
public record UserContact(String userId, String email, String phone) {

    /**
     * Converts to the recipient the notification dispatcher takes.
     *
     * @param name display name for greetings, or {@code null}
     * @return the recipient
     */
    public Recipient toRecipient(String name) {
        return Recipient.builder().userId(userId).name(name).email(email).phone(phone).build();
    }

    /**
     * Whether the user can be reached at all.
     *
     * @return {@code true} when at least one destination is known
     */
    public boolean isReachable() {
        return (email != null && !email.isBlank()) || (phone != null && !phone.isBlank());
    }

    /**
     * Masks both destinations, so a contact never reaches the logs through the logging aspect.
     *
     * @return a log-safe representation
     */
    @Override
    public String toString() {
        return "UserContact[userId=" + userId + ", email=" + (email == null ? "none" : "set")
                + ", phone=" + (phone == null ? "none" : "set") + "]";
    }
}
