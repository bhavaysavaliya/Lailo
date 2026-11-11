package com.lailo.application.common.notification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * The one place a feature asks for a user to be notified. It fans a rendered
 * {@link NotificationRequest} out to the channel ports and makes the whole thing idempotent.
 *
 * <p>Why a dispatcher rather than calling {@link EmailSender} directly: notifications are raised from Kafka
 * consumers, and delivery there is at-least-once. The claim row keyed by
 * {@code (dedupeKey, channel)} turns that into exactly-once-per-channel in practice - a redelivered event
 * finds a {@code SENT} row and sends nothing.</p>
 *
 * <p>A provider failure is recorded as {@code FAILED} and then re-signalled, so the consumer's retry
 * machinery drives the retry; the next attempt re-claims the failed row instead of being treated as a
 * duplicate.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationDispatcher {

    /** A {@code PENDING} claim older than this was left behind by a crashed instance and may be retaken. */
    private static final Duration STALE_CLAIM = Duration.ofMinutes(5);

    private final EmailSender emailSender;
    private final MessageSender messageSender;
    private final NotificationDeliveryRepository deliveries;
    private final Clock clock;

    /**
     * Delivers the request on every channel it carries content for. Channels are independent: a failing SMS
     * does not undo a sent email, and only the failing one is retried.
     *
     * @param request the rendered notification
     * @return completion signal; an error means at least one channel failed and should be retried
     */
    public Mono<Void> dispatch(NotificationRequest request) {
        return Flux.concat(email(request), sms(request)).then();
    }

    /**
     * Sends the email part, if any.
     *
     * @param request the rendered notification
     * @return completion signal, empty when this request has no email to send
     */
    private Mono<Void> email(NotificationRequest request) {
        if (!request.wantsEmail()) {
            return Mono.empty();
        }
        EmailMessage message = EmailMessage.builder()
                .to(request.recipient().email())
                .subject(request.subject())
                .textBody(request.emailBody())
                .htmlBody(request.htmlBody())
                .build();
        return deliver(request, NotificationChannel.EMAIL, request.recipient().email(), request.subject(),
                Mono.defer(() -> emailSender.send(message)));
    }

    /**
     * Sends the text-message part, if any.
     *
     * @param request the rendered notification
     * @return completion signal, empty when this request has no text message to send
     */
    private Mono<Void> sms(NotificationRequest request) {
        if (!request.wantsSms()) {
            return Mono.empty();
        }
        TextMessage message = new TextMessage(request.recipient().phone(), request.smsBody());
        return deliver(request, NotificationChannel.SMS, request.recipient().phone(), request.subject(),
                Mono.defer(() -> messageSender.send(message)));
    }

    /**
     * Claims the channel, calls the provider and records the outcome.
     *
     * @param request the rendered notification
     * @param channel channel being delivered on
     * @param destination raw destination, stored masked
     * @param subject subject or label stored with the claim
     * @param send the provider call
     * @return completion signal; errors after the failure has been recorded
     */
    private Mono<Void> deliver(NotificationRequest request, NotificationChannel channel, String destination,
                               String subject, Mono<Void> send) {
        return claim(request.dedupeKey(), channel, destination, subject)
                .flatMap(claim -> send
                        .then(deliveries.save(claim.sent(Instant.now(clock))))
                        .doOnSuccess(saved -> log.debug("Sent {} notification {} to {}", channel,
                                request.dedupeKey(), saved.getDestination()))
                        .onErrorResume(ex -> {
                            log.warn("{} notification {} failed: {}", channel, request.dedupeKey(), ex.getMessage());
                            return deliveries.save(claim.failed(ex.getMessage())).then(Mono.error(ex));
                        }))
                .then();
    }

    /**
     * Takes ownership of one channel for one dedupe key.
     *
     * <p>The insert is the lock: when the unique index rejects it, an attempt is already recorded, and only a
     * failed or abandoned attempt may be retaken.</p>
     *
     * @param dedupeKey idempotency key
     * @param channel channel to claim
     * @param destination raw destination, stored masked
     * @param subject subject or label stored with the claim
     * @return the claim to send under, or empty when another attempt already owns it
     */
    private Mono<NotificationDelivery> claim(String dedupeKey, NotificationChannel channel, String destination,
                                             String subject) {
        Instant now = Instant.now(clock);
        return deliveries.save(NotificationDelivery.claim(dedupeKey, channel, Masks.mask(destination), subject, now))
                .onErrorResume(DuplicateKeyException.class, ex ->
                        deliveries.findByDedupeKeyAndChannel(dedupeKey, channel)
                                .flatMap(existing -> retakeable(existing, now)
                                        ? deliveries.save(existing.retry())
                                        : skip(dedupeKey, channel, existing)));
    }

    /**
     * Decides whether an existing attempt may be retaken: a failure may be retried, and so may a claim whose
     * owner never finished (the instance holding it died mid-send).
     *
     * @param existing the recorded attempt
     * @param now current time
     * @return {@code true} when this dispatcher may send under the existing row
     */
    private static boolean retakeable(NotificationDelivery existing, Instant now) {
        return existing.getStatus() == DeliveryStatus.FAILED
                || (existing.getStatus() == DeliveryStatus.PENDING
                && existing.getCreatedAt().isBefore(now.minus(STALE_CLAIM)));
    }

    /**
     * Logs and drops a duplicate delivery.
     *
     * @param dedupeKey idempotency key
     * @param channel channel that was already handled
     * @param existing the recorded attempt
     * @return always empty
     */
    private static Mono<NotificationDelivery> skip(String dedupeKey, NotificationChannel channel,
                                                   NotificationDelivery existing) {
        log.debug("Skipped duplicate {} notification {} ({})", channel, dedupeKey, existing.getStatus());
        return Mono.empty();
    }
}
