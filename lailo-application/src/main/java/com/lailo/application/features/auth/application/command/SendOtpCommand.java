package com.lailo.application.features.auth.application.command;

import com.lailo.application.common.cqrs.Command;
import com.lailo.application.common.web.ClientInfo;

public record SendOtpCommand(String identifier, String captchaId, String captchaAnswer, ClientInfo client)
        implements Command<SendOtpCommand.Result> {

    public record Result(String requestId, String identifierType, String maskedIdentifier,
                         long expiresInSeconds, long resendAfterSeconds) {
    }

    @Override
    public String toString() {
        return "SendOtpCommand[ip=" + client.ipAddress() + "]";
    }
}
