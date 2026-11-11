package com.lailo.application.common.notification;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.With;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One attempted delivery, written before the provider is called. It makes notifications idempotent:
 * events reach consumers at least once, and nobody wants two "order placed" texts for one order.
 *
 * <p>The unique index on {@code (dedupe_key, channel)} is what enforces that - the row is the claim.</p>
 */
@Table("notification_deliveries")
@Getter
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationDelivery {

    @Id
    private UUID id;
    /** Caller-chosen idempotency key; an event id is the usual choice. */
    private String dedupeKey;
    private NotificationChannel channel;
    /** Masked destination: enough to debug a delivery, not enough to leak a contact list. */
    private String destination;
    private String subject;
    private DeliveryStatus status;
    private String failureReason;
    private int attempts;
    private Instant createdAt;
    private Instant sentAt;
    /** Optimistic locking; null marks a new entity for Spring Data. */
    @Version
    private Long version;

    /**
     * Claims a delivery for one channel.
     *
     * @param dedupeKey idempotency key
     * @param channel channel being used
     * @param destination masked destination
     * @param subject email subject, or a short label for other channels
     * @param now claim time
     * @return an unsaved row with a {@code null} version, so Spring Data inserts it
     */
    public static NotificationDelivery claim(String dedupeKey, NotificationChannel channel, String destination,
                                             String subject, Instant now) {
        return NotificationDelivery.builder()
                .id(UUID.randomUUID())
                .dedupeKey(dedupeKey)
                .channel(channel)
                .destination(destination)
                .subject(subject)
                .status(DeliveryStatus.PENDING)
                .attempts(1)
                .createdAt(now)
                .build();
    }

    /**
     * Returns a copy marked as delivered.
     *
     * @param now delivery time
     * @return the sent row
     */
    public NotificationDelivery sent(Instant now) {
        return withStatus(DeliveryStatus.SENT).withSentAt(now).withFailureReason(null);
    }

    /**
     * Returns a copy marked as failed, keeping the reason for support and alerting.
     *
     * @param reason provider error, truncated to the column width
     * @return the failed row
     */
    public NotificationDelivery failed(String reason) {
        String safe = reason == null ? "unknown" : reason.substring(0, Math.min(reason.length(), 500));
        return withStatus(DeliveryStatus.FAILED).withFailureReason(safe);
    }

    /**
     * Returns a copy re-claimed for another attempt after an earlier failure.
     *
     * @return the row back in {@code PENDING} with its attempt counter raised
     */
    public NotificationDelivery retry() {
        return withStatus(DeliveryStatus.PENDING).withAttempts(attempts + 1);
    }
}
