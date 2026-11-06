package com.lailo.application.features.auth.api;

import com.lailo.application.common.cqrs.CommandBus;
import com.lailo.application.common.web.ClientInfo;
import com.lailo.application.features.auth.api.dto.SendOtpRequest;
import com.lailo.application.features.auth.api.dto.SignInResponse;
import com.lailo.application.features.auth.api.dto.VerifyOtpRequest;
import com.lailo.application.features.auth.application.AuthResult;
import com.lailo.application.features.auth.application.command.IssueCaptchaCommand;
import com.lailo.application.features.auth.application.command.LogoutCommand;
import com.lailo.application.features.auth.application.command.RefreshSessionCommand;
import com.lailo.application.features.auth.application.command.SendOtpCommand;
import com.lailo.application.features.auth.application.command.VerifyOtpCommand;
import com.lailo.application.features.auth.infrastructure.captcha.CaptchaChallenge;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Optional;

/** Thin adapter: HTTP -> commands. No business logic lives here. */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final CommandBus commandBus;
    private final AuthCookies cookies;

    /** No-op; the security filter chain attaches the XSRF-TOKEN cookie to the response. */
    @GetMapping("/csrf")
    public Mono<ResponseEntity<Void>> csrf() {
        return Mono.just(ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build());
    }

    @GetMapping("/captcha")
    public Mono<ResponseEntity<CaptchaChallenge>> captcha(ServerWebExchange exchange) {
        return commandBus.dispatch(new IssueCaptchaCommand(ClientInfo.from(exchange.getRequest())))
                .map(c -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(c));
    }

    @PostMapping("/otp")
    public Mono<SendOtpCommand.Result> sendOtp(@Valid @RequestBody SendOtpRequest req, ServerWebExchange exchange) {
        return commandBus.dispatch(new SendOtpCommand(req.identifier(), req.captchaId(), req.captchaAnswer(),
                ClientInfo.from(exchange.getRequest())));
    }

    @PostMapping("/otp/verify")
    public Mono<SignInResponse> verifyOtp(@Valid @RequestBody VerifyOtpRequest req, ServerWebExchange exchange) {
        return commandBus.dispatch(new VerifyOtpCommand(req.requestId(), req.otp(), ClientInfo.from(exchange.getRequest())))
                .map(result -> respond(result, exchange));
    }

    @PostMapping("/refresh")
    public Mono<SignInResponse> refresh(ServerWebExchange exchange) {
        return commandBus.dispatch(new RefreshSessionCommand(refreshToken(exchange), ClientInfo.from(exchange.getRequest())))
                .map(result -> respond(result, exchange))
                .doOnError(ex -> cookies.clear(exchange.getResponse()));
    }

    @PostMapping("/logout")
    public Mono<ResponseEntity<Void>> logout(ServerWebExchange exchange) {
        return commandBus.dispatch(new LogoutCommand(refreshToken(exchange)))
                .then(Mono.fromRunnable(() -> cookies.clear(exchange.getResponse())))
                .thenReturn(ResponseEntity.noContent().build());
    }

    private SignInResponse respond(AuthResult result, ServerWebExchange exchange) {
        cookies.write(exchange.getResponse(), result);
        exchange.getResponse().getHeaders().setCacheControl(CacheControl.noStore());
        return new SignInResponse(result.userId().toString(), result.newUser(), result.accessToken().expiresAt());
    }

    private String refreshToken(ServerWebExchange exchange) {
        return Optional.ofNullable(exchange.getRequest().getCookies().getFirst(cookies.refreshCookieName()))
                .map(HttpCookie::getValue)
                .orElse(null);
    }
}
