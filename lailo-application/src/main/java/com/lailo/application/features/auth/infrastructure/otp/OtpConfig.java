package com.lailo.application.features.auth.infrastructure.otp;

import com.lailo.application.features.auth.config.AuthProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.security.SecureRandom;

/** Wires OTP generation and the fallback delivery channel. */
@Slf4j
@Configuration
public class OtpConfig {

    /**
     * Chooses the code generator: the fixed development code when {@code lailo.auth.otp.fixed-code} is set
     * (logging a warning), otherwise uniformly random digits.
     *
     * @param props OTP settings
     * @param random secure random source
     * @return the generator
     */
    @Bean
    OtpGenerator otpGenerator(AuthProperties props, SecureRandom random) {
        String fixed = props.otp().fixedCode();
        if (fixed != null && !fixed.isBlank()) {
            log.warn("lailo.auth.otp.fixed-code is set: every OTP is '{}'. Never enable this outside local development.",
                    "*".repeat(fixed.length()));
            return length -> fixed;
        }
        return length -> {
            StringBuilder sb = new StringBuilder(length);
            for (int i = 0; i < length; i++) {
                sb.append(random.nextInt(10));
            }
            return sb.toString();
        };
    }

    /** Replace with an SMS / email provider bean (e.g. MSG91, Twilio, SES) - this one only logs. */
    @Bean
    @ConditionalOnMissingBean(OtpSender.class)
    OtpSender loggingOtpSender() {
        return new LoggingOtpSender();
    }
}
