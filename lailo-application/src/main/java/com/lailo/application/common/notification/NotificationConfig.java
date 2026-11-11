package com.lailo.application.common.notification;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the notification channels. Both ports fall back to a logging adapter, so the application runs with no
 * provider credentials; adding a real {@link EmailSender} or {@link MessageSender} bean replaces one channel
 * without touching any feature code.
 */
@Configuration
@EnableConfigurationProperties(NotificationProperties.class)
public class NotificationConfig {

    /** Replace with a provider adapter (SES, Postmark, MSG91) - this one only logs. */
    @Bean
    @ConditionalOnMissingBean(EmailSender.class)
    EmailSender loggingEmailSender() {
        return new LoggingEmailSender();
    }

    /** Replace with a provider adapter (MSG91, Twilio, Gupshup) - this one only logs. */
    @Bean
    @ConditionalOnMissingBean(MessageSender.class)
    MessageSender loggingMessageSender() {
        return new LoggingMessageSender();
    }
}
