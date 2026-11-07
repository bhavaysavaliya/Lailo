package com.lailo.application.features.auth.application.command;

import com.lailo.application.common.cqrs.Command;
import com.lailo.application.common.web.ClientInfo;
import com.lailo.application.features.auth.application.AuthResult;

/**
 * Exchange a refresh token for a new token pair.
 *
 * @param refreshToken token from the cookie; may be {@code null}
 * @param client caller metadata, stored on the new session
 */
public record RefreshSessionCommand(String refreshToken, ClientInfo client) implements Command<AuthResult> {
    /**
     * Omits the token so it never reaches logs.
     *
     * @return a log-safe representation
     */
    @Override
    public String toString() {
        return "RefreshSessionCommand[ip=" + client.ipAddress() + "]";
    }
}
