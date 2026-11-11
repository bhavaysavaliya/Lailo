package com.lailo.application.common.notification;

import com.lailo.application.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.core.publisher.Mono;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * The shared notification dispatcher against real PostgreSQL.
 *
 * <p>The two providers are spied rather than mocked: the real logging adapters still run, and the spy only
 * exists so a test can count deliveries and force a provider failure. Everything else - the claim rows, the
 * deduplication, the retry - is the production code path.</p>
 */
class NotificationDispatcherIT extends IntegrationTest {

    @Autowired NotificationDispatcher dispatcher;
    @Autowired NotificationDeliveryRepository deliveries;

    /**
     * Builds a notification addressed to both channels.
     *
     * @param dedupeKey idempotency key
     * @return the request
     */
    private static NotificationRequest bothChannels(String dedupeKey) {
        return NotificationRequest.builder()
                .dedupeKey(dedupeKey)
                .recipient(Recipient.builder()
                        .userId(UUID.randomUUID().toString())
                        .name("Aarav Mehta")
                        .email("aarav@example.com")
                        .phone("+919876543210")
                        .build())
                .subject("Lailo order LAILO-20261111-K7M2QX is placed")
                .emailBody("Thanks for your order.")
                .smsBody("Lailo: order LAILO-20261111-K7M2QX placed.")
                .build();
    }

    /**
     * A notification goes out on every channel the recipient can be reached on.
     * <ul>
     *   <li><b>Given</b> a recipient with both an email address and a phone number</li>
     *   <li><b>When</b> the notification is dispatched</li>
     *   <li><b>Then</b> both providers are called once and both deliveries are recorded as sent, with the
     *       destination stored masked so the table is not a contact list</li>
     * </ul>
     */
    @Test
    void sendsOnEveryChannelTheRecipientCanBeReachedOn() {
        String key = "evt-" + UUID.randomUUID();

        dispatcher.dispatch(bothChannels(key)).block();

        verify(emailSender, times(1)).send(any());
        verify(messageSender, times(1)).send(any());
        NotificationDelivery email = deliveries.findByDedupeKeyAndChannel(key, NotificationChannel.EMAIL).block();
        NotificationDelivery sms = deliveries.findByDedupeKeyAndChannel(key, NotificationChannel.SMS).block();
        assertThat(email.getStatus()).isEqualTo(DeliveryStatus.SENT);
        assertThat(email.getSentAt()).isNotNull();
        assertThat(email.getDestination()).isEqualTo("a***@example.com").doesNotContain("aarav@");
        assertThat(sms.getStatus()).isEqualTo(DeliveryStatus.SENT);
        assertThat(sms.getDestination()).doesNotContain("9876543");
    }

    /**
     * A redelivered event does not produce a second message.
     * <ul>
     *   <li><b>Given</b> a notification that has already been delivered</li>
     *   <li><b>When</b> the identical request is dispatched again, as at-least-once Kafka delivery
     *       guarantees will eventually cause</li>
     *   <li><b>Then</b> neither provider is called a second time: the claim row keyed by dedupe key and
     *       channel is what makes it exactly once per channel</li>
     * </ul>
     */
    @Test
    void doesNotSendTwiceForARedeliveredEvent() {
        String key = "evt-" + UUID.randomUUID();
        dispatcher.dispatch(bothChannels(key)).block();

        dispatcher.dispatch(bothChannels(key)).block();

        verify(emailSender, times(1)).send(any());
        verify(messageSender, times(1)).send(any());
    }

    /**
     * A request with no SMS content leaves the phone alone.
     * <ul>
     *   <li><b>Given</b> a notification with an email body only</li>
     *   <li><b>When</b> it is dispatched to a recipient who also has a phone number</li>
     *   <li><b>Then</b> only the email is sent and no SMS delivery is recorded, so a caller chooses a
     *       channel by what it renders rather than by branching on the recipient</li>
     * </ul>
     */
    @Test
    void skipsAChannelWithNoContent() {
        String key = "evt-" + UUID.randomUUID();

        dispatcher.dispatch(NotificationRequest.builder()
                .dedupeKey(key)
                .recipient(Recipient.builder().userId(UUID.randomUUID().toString())
                        .email("email-only@example.com").phone("+919876543210").build())
                .subject("Email only")
                .emailBody("No text message for this one.")
                .build()).block();

        verify(emailSender, times(1)).send(any());
        verifyNoInteractions(messageSender);
        assertThat(deliveries.findByDedupeKeyAndChannel(key, NotificationChannel.SMS).block()).isNull();
    }

    /**
     * A recipient with no email is simply not emailed.
     * <ul>
     *   <li><b>Given</b> a recipient reachable only by phone</li>
     *   <li><b>When</b> a notification with both bodies is dispatched</li>
     *   <li><b>Then</b> only the text message is sent</li>
     * </ul>
     */
    @Test
    void skipsAChannelWithNoDestination() {
        String key = "evt-" + UUID.randomUUID();

        dispatcher.dispatch(NotificationRequest.builder()
                .dedupeKey(key)
                .recipient(Recipient.builder().userId(UUID.randomUUID().toString())
                        .phone("+919876543210").build())
                .subject("Phone only")
                .emailBody("Nobody will read this.")
                .smsBody("Lailo: phone only.")
                .build()).block();

        verifyNoInteractions(emailSender);
        verify(messageSender, times(1)).send(any());
        assertThat(deliveries.findByDedupeKeyAndChannel(key, NotificationChannel.EMAIL).block()).isNull();
    }

    /**
     * A provider failure is recorded, reported, and then retried successfully.
     * <ul>
     *   <li><b>Given</b> an email provider that rejects the first attempt</li>
     *   <li><b>When</b> the notification is dispatched, and dispatched again once the provider recovers</li>
     *   <li><b>Then</b> the first attempt errors - so the consumer's retry machinery sees it - and leaves a
     *       {@code FAILED} row; the retry re-claims that row and sends, which is why a failure is retried
     *       while a success is not</li>
     * </ul>
     */
    @Test
    void recordsAFailureAndLetsTheRetrySucceed() {
        String key = "evt-" + UUID.randomUUID();
        doReturn(Mono.error(new IllegalStateException("provider unavailable")))
                .when(emailSender).send(any());

        assertThatThrownBy(() -> dispatcher.dispatch(bothChannels(key)).block())
                .hasMessageContaining("provider unavailable");

        NotificationDelivery failed = deliveries.findByDedupeKeyAndChannel(key, NotificationChannel.EMAIL).block();
        assertThat(failed.getStatus()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(failed.getFailureReason()).contains("provider unavailable");
        assertThat(failed.getAttempts()).isEqualTo(1);

        doReturn(Mono.empty()).when(emailSender).send(any());
        dispatcher.dispatch(bothChannels(key)).block();

        NotificationDelivery retried = deliveries.findByDedupeKeyAndChannel(key, NotificationChannel.EMAIL).block();
        assertThat(retried.getStatus()).isEqualTo(DeliveryStatus.SENT);
        assertThat(retried.getAttempts()).isEqualTo(2);
    }

    /**
     * One channel failing does not undo the other.
     * <ul>
     *   <li><b>Given</b> a working email provider and an SMS provider that fails</li>
     *   <li><b>When</b> a notification for both channels is dispatched</li>
     *   <li><b>Then</b> the email is still recorded as sent while the SMS is recorded as failed, so a retry
     *       resends only the one that did not arrive</li>
     * </ul>
     */
    @Test
    void keepsChannelsIndependentWhenOneFails() {
        String key = "evt-" + UUID.randomUUID();
        doReturn(Mono.error(new IllegalStateException("sms gateway down")))
                .when(messageSender).send(any());

        assertThatThrownBy(() -> dispatcher.dispatch(bothChannels(key)).block())
                .hasMessageContaining("sms gateway down");

        assertThat(deliveries.findByDedupeKeyAndChannel(key, NotificationChannel.EMAIL).block().getStatus())
                .isEqualTo(DeliveryStatus.SENT);
        assertThat(deliveries.findByDedupeKeyAndChannel(key, NotificationChannel.SMS).block().getStatus())
                .isEqualTo(DeliveryStatus.FAILED);
    }
}
