package com.lailo.application.features.auth.application.command;

import com.lailo.application.common.cqrs.Command;

public record LogoutCommand(String refreshToken) implements Command<Boolean> {
    @Override
    public String toString() {
        return "LogoutCommand[]";
    }
}
