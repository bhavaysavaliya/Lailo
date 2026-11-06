package com.lailo.application.features.auth.application.command;

import com.lailo.application.common.cqrs.Command;
import com.lailo.application.common.web.ClientInfo;
import com.lailo.application.features.auth.application.AuthResult;

public record VerifyOtpCommand(String requestId, String code, ClientInfo client) implements Command<AuthResult> {
    @Override
    public String toString() {
        return "VerifyOtpCommand[requestId=" + requestId + ", code=******]";
    }
}
