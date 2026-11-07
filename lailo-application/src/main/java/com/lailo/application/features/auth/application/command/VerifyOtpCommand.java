package com.lailo.application.features.auth.application.command;

import com.lailo.application.common.cqrs.Command;
import com.lailo.application.common.web.ClientInfo;
import com.lailo.application.features.auth.application.AuthResult;

/**
 * Request to verify a one-time code and sign in.
 *
 * @param requestId id from the send-OTP response
 * @param code the code entered by the user
 * @param client caller metadata, stored on the new session
 */
public record VerifyOtpCommand(String requestId, String code, ClientInfo client) implements Command<AuthResult> {
    /**
     * Omits the code so it never reaches logs.
     *
     * @return a log-safe representation
     */
    @Override
    public String toString() {
        return "VerifyOtpCommand[requestId=" + requestId + ", code=******]";
    }
}
