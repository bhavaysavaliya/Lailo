package com.lailo.application.features.auth.infrastructure.otp;

import com.lailo.application.common.notification.EmailMessage;
import com.lailo.application.common.notification.EmailSender;
import com.lailo.application.common.notification.MessageSender;
import com.lailo.application.common.notification.NotificationProperties;
import com.lailo.application.common.notification.TextMessage;
import com.lailo.application.features.auth.config.AuthProperties;
import com.lailo.application.features.auth.domain.Identifier;
import com.lailo.application.features.auth.domain.IdentifierType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * Delivers sign-in codes over the shared notification channels: email addresses get an email, mobile numbers
 * get a text message. Auth therefore has no provider of its own - configuring
 * {@link EmailSender} / {@link MessageSender} once serves OTPs, order updates and everything after them.
 *
 * <p>It calls the channel ports directly instead of going through
 * {@code NotificationDispatcher}: an OTP must never be deduplicated, because every resend is a new code the
 * user is waiting for.</p>
 */
@Slf4j
@RequiredArgsConstructor
public class NotificationOtpSender implements OtpSender {

    private final EmailSender emailSender;
    private final MessageSender messageSender;
    private final NotificationProperties notifications;
    private final AuthProperties auth;

    /**
     * Renders and delivers the code on the channel the identifier implies.
     *
     * @param destination email or phone the user typed, already normalised
     * @param code the plain code; it is never logged
     * @return completion signal; an error means the provider rejected the message
     */
    @Override
    public Mono<Void> send(Identifier destination, String code) {
        long minutes = Math.max(1, auth.otp().ttl().toMinutes());
        Mono<Void> delivery = destination.type() == IdentifierType.EMAIL
                ? emailSender.send(email(destination, code, minutes))
                : messageSender.send(text(destination, code, minutes));
        return delivery.doOnSuccess(ignored -> log.debug("OTP delivered to {} via {}",
                destination.masked(), destination.type()));
    }

    /**
     * Builds the OTP email.
     *
     * @param destination verified address
     * @param code the plain code
     * @param minutes how long the code stays valid
     * @return the message to deliver
     */
    private EmailMessage email(Identifier destination, String code, long minutes) {
        String brand = notifications.brandName();
        return EmailMessage.builder()
                .to(destination.value())
                .subject(code + " is your " + brand + " sign-in code")
                .textBody("""
                        Your %s sign-in code is %s.

                        It expires in %d minutes and can be used once.
                        If you didn't ask to sign in, you can ignore this email.

                        - %s
                        """.formatted(brand, code, minutes, brand))
                .build();
    }

    /**
     * Builds the OTP text message.
     *
     * @param destination verified mobile number
     * @param code the plain code
     * @param minutes how long the code stays valid
     * @return the message to deliver
     */
    private TextMessage text(Identifier destination, String code, long minutes) {
        return new TextMessage(destination.value(),
                "%s: %s is your sign-in code. It expires in %d minutes. Never share it."
                        .formatted(notifications.brandName(), code, minutes));
    }
}
