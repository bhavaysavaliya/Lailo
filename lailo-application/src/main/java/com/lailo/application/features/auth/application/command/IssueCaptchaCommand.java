package com.lailo.application.features.auth.application.command;

import com.lailo.application.common.cqrs.Command;
import com.lailo.application.common.web.ClientInfo;
import com.lailo.application.features.auth.infrastructure.captcha.CaptchaChallenge;

public record IssueCaptchaCommand(ClientInfo client) implements Command<CaptchaChallenge> {
}
