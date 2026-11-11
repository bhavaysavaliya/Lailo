package com.lailo.application.common.notification;

/**
 * One short message (SMS today, WhatsApp or push later) addressed to a mobile number.
 *
 * @param to destination in E.164, e.g. {@code +919876543210}
 * @param body message text; keep it within a single SMS segment where possible
 */
public record TextMessage(String to, String body) {

    /** Longest body a sender should accept, so a template bug cannot cost a 10-segment SMS. */
    public static final int MAX_LENGTH = 480;

    /**
     * Rejects a message no provider could accept.
     */
    public TextMessage {
        if (to == null || to.isBlank()) {
            throw new IllegalArgumentException("to is required");
        }
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("body is required");
        }
        if (body.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("body must be at most " + MAX_LENGTH + " characters");
        }
    }

    /**
     * Masks the destination and omits the body.
     *
     * @return a log-safe representation
     */
    @Override
    public String toString() {
        return "TextMessage[to=" + Masks.mask(to) + ", length=" + body.length() + "]";
    }
}
