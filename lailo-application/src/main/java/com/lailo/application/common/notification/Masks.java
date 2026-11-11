package com.lailo.application.common.notification;

/**
 * Masking used by every notification type's {@code toString()}. The logging aspect prints method arguments at
 * DEBUG, so an unmasked address or number would end up in the log files of every service that notifies.
 */
final class Masks {

    /** Not instantiable: static helper. */
    private Masks() {
    }

    /**
     * Hides all but the first and last characters of a destination.
     *
     * @param value an email address or phone number, possibly {@code null}
     * @return a log-safe value, {@code "null"} when absent
     */
    static String mask(String value) {
        if (value == null || value.isBlank()) {
            return "null";
        }
        int at = value.indexOf('@');
        if (at > 0) {
            return value.charAt(0) + "***" + value.substring(at);
        }
        int keep = Math.min(3, value.length());
        return value.substring(0, keep) + "*".repeat(Math.max(0, value.length() - keep - 2))
                + value.substring(Math.max(keep, value.length() - 2));
    }
}
