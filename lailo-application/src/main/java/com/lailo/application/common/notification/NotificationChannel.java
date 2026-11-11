package com.lailo.application.common.notification;

/** Delivery channels a notification can use. One delivery row is recorded per channel. */
public enum NotificationChannel {
    /** Transactional email. */
    EMAIL,
    /** Short message to a mobile number (SMS today). */
    SMS
}
