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

    /**
     * Issues a captcha challenge. Responses are {@code no-store} and also seed the {@code XSRF-TOKEN} cookie.
     *
     * @param exchange current exchange (used for the caller's IP)
     * @return the challenge: image and id for the built-in provider, or a site key for Turnstile
     */
    @GetMapping("/captcha")
    public Mono<ResponseEntity<CaptchaChallenge>> captcha(ServerWebExchange exchange) {
        return commandBus.dispatch(new IssueCaptchaCommand(ClientInfo.from(exchange.getRequest())))
                .map(c -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(c));
    }

    /**
     * Step 1 of sign-in: validates the captcha and sends a one-time code to the email or mobile number.
     *
     * @param req identifier and captcha answer
     * @param exchange current exchange (used for the caller's IP)
     * @return the OTP request id, masked identifier, code lifetime and resend delay
     */
    @PostMapping("/otp")
    public Mono<SendOtpCommand.Result> sendOtp(@Valid @RequestBody SendOtpRequest req, ServerWebExchange exchange) {
        return commandBus.dispatch(new SendOtpCommand(req.identifier(), req.captchaId(), req.captchaAnswer(),
                ClientInfo.from(exchange.getRequest())));
    }

    /**
     * Step 2 of sign-in: verifies the code, creating the account on first use, and sets the auth cookies.
     *
     * @param req OTP request id and the code the user entered
     * @param exchange current exchange (cookies are written to its response)
     * @return user id and whether the account was just created; tokens are only in cookies
     */
    @PostMapping("/otp/verify")
    public Mono<SignInResponse> verifyOtp(@Valid @RequestBody VerifyOtpRequest req, ServerWebExchange exchange) {
        return commandBus.dispatch(new VerifyOtpCommand(req.requestId(), req.otp(), ClientInfo.from(exchange.getRequest())))
                .map(result -> respond(result, exchange));
    }

    /**
     * Rotates the refresh token read from its cookie and sets fresh auth cookies. On failure the cookies are
     * cleared so the browser stops sending a dead session.
     *
     * @param exchange current exchange
     * @return the same body as sign-in, with {@code newUser=false}
     */
    @PostMapping("/refresh")
    public Mono<SignInResponse> refresh(ServerWebExchange exchange) {
        return commandBus.dispatch(new RefreshSessionCommand(refreshToken(exchange), ClientInfo.from(exchange.getRequest())))
                .map(result -> respond(result, exchange))
                .doOnError(ex -> cookies.clear(exchange.getResponse()));
    }

    /**
     * Revokes the session family of the presented refresh token and clears the auth cookies. Idempotent.
     *
     * @param exchange current exchange
     * @return 204 No Content, also when there was no valid session
     */
    @PostMapping("/logout")
    public Mono<ResponseEntity<Void>> logout(ServerWebExchange exchange) {
        return commandBus.dispatch(new LogoutCommand(refreshToken(exchange)))
                .then(Mono.fromRunnable(() -> cookies.clear(exchange.getResponse())))
                .thenReturn(ResponseEntity.noContent().build());
    }

    /**
     * Writes the auth cookies, marks the response uncacheable and builds the JSON body.
     *
     * @param result tokens and user from the handler
     * @param exchange current exchange
     * @return the response body (never contains tokens)
     */
    private SignInResponse respond(AuthResult result, ServerWebExchange exchange) {
        cookies.write(exchange.getResponse(), result);
        exchange.getResponse().getHeaders().setCacheControl(CacheControl.noStore());
        return new SignInResponse(result.userId().toString(), result.newUser(), result.accessToken().expiresAt());
    }

    /**
     * Reads the refresh token from its path-scoped cookie.
     *
     * @param exchange current exchange
     * @return the token, or {@code null} if the cookie is absent
     */
    private String refreshToken(ServerWebExchange exchange) {
        return Optional.ofNullable(exchange.getRequest().getCookies().getFirst(cookies.refreshCookieName()))
                .map(HttpCookie::getValue)
                .orElse(null);
    }
}
