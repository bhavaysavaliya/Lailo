package com.lailo.application.features.auth.application.command;

import com.lailo.application.common.cqrs.Command;
import com.lailo.application.common.web.ClientInfo;
import com.lailo.application.features.auth.application.AuthResult;

public record RefreshSessionCommand(String refreshToken, ClientInfo client) implements Command<AuthResult> {
    @Override
    public String toString() {
        return "RefreshSessionCommand[ip=" + client.ipAddress() + "]";
    }
}
