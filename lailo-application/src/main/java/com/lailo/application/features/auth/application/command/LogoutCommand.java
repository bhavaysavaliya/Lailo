package com.lailo.application.features.auth.application.command;

import com.lailo.application.common.cqrs.Command;

/**
 * Sign out of the current device.
 *
 * @param refreshToken the refresh token from the cookie; may be {@code null}
 */
public record LogoutCommand(String refreshToken) implements Command<Boolean> {
    /**
     * Omits the token so it never reaches logs.
     *
     * @return a log-safe representation
     */
    @Override
    public String toString() {
        return "LogoutCommand[]";
    }
}
