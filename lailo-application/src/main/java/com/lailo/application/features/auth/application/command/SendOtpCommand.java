package com.lailo.application.features.auth.application.command;

import com.lailo.application.common.cqrs.Command;
import com.lailo.application.common.web.ClientInfo;

/**
 * Request to send a one-time code.
 *
 * @param identifier email or mobile number as typed
 * @param captchaId captcha challenge id (built-in provider)
 * @param captchaAnswer captcha answer or Turnstile token
 * @param client caller metadata, used for rate limiting and captcha verification
 */
public record SendOtpCommand(String identifier, String captchaId, String captchaAnswer, ClientInfo client)
        implements Command<SendOtpCommand.Result> {

    /**
     * Outcome of a successful send.
     *
     * @param requestId opaque id the client must present with the code
     * @param identifierType {@code EMAIL} or {@code PHONE}
     * @param maskedIdentifier destination safe to show, e.g. {@code +91******3210}
     * @param expiresInSeconds code lifetime
     * @param resendAfterSeconds cooldown before another code can be requested
     */
    public record Result(String requestId, String identifierType, String maskedIdentifier,
                         long expiresInSeconds, long resendAfterSeconds) {
    }

    /**
     * Omits the identifier and captcha answer so they never reach logs.
     *
     * @return a log-safe representation
     */
    @Override
    public String toString() {
        return "SendOtpCommand[ip=" + client.ipAddress() + "]";
    }
}
