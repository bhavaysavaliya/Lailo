package com.lailo.application.features.auth.application.command;

import com.lailo.application.common.cqrs.Command;
import com.lailo.application.common.web.ClientInfo;
import com.lailo.application.features.auth.infrastructure.captcha.CaptchaChallenge;

/**
 * Request for a new captcha challenge.
 *
 * @param client caller metadata, used for per-IP rate limiting
 */
public record IssueCaptchaCommand(ClientInfo client) implements Command<CaptchaChallenge> {
}
