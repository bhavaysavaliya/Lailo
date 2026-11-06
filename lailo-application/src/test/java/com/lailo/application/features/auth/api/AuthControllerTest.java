package com.lailo.application.features.auth.api;

import com.lailo.application.common.cqrs.CommandBus;
import com.lailo.application.common.cqrs.QueryBus;
import com.lailo.application.features.auth.application.AuthErrors;
import com.lailo.application.features.auth.application.AuthResult;
import com.lailo.application.features.auth.application.command.IssueCaptchaCommand;
import com.lailo.application.features.auth.application.command.LogoutCommand;
import com.lailo.application.features.auth.application.command.RefreshSessionCommand;
import com.lailo.application.features.auth.application.command.SendOtpCommand;
import com.lailo.application.features.auth.application.command.VerifyOtpCommand;
import com.lailo.application.features.auth.application.query.GetCurrentUserQuery;
import com.lailo.application.features.auth.application.query.UserProfileView;
import com.lailo.application.features.auth.config.AuthBeansConfig;
import com.lailo.application.features.auth.domain.Identifier;
import com.lailo.application.features.auth.domain.IdentifierType;
import com.lailo.application.features.auth.domain.User;
import com.lailo.application.features.auth.infrastructure.captcha.CaptchaChallenge;
import com.lailo.application.features.auth.infrastructure.token.AccessToken;
import com.lailo.application.features.auth.infrastructure.token.JwtTokenService;
import com.lailo.application.security.auth.config.AuthConfig;
import com.lailo.application.support.TestAuthProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.csrf;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

/** HTTP contract of the auth API: validation, CSRF, cookies and error format. Handlers are mocked. */
@WebFluxTest(controllers = {AuthController.class, UserController.class})
@Import({AuthConfig.class, AuthCookies.class, AuthBeansConfig.class, JwtTokenService.class})
@TestPropertySource(properties = {
        "lailo.auth.otp.hash-secret=" + TestAuthProperties.OTP_SECRET,
        "lailo.auth.jwt.secret=" + TestAuthProperties.JWT_SECRET,
        "lailo.auth.cookies.secure=true"
})
class AuthControllerTest {

    @Autowired WebTestClient client;
    @Autowired JwtTokenService jwtTokenService;
    @MockitoBean CommandBus commandBus;
    @MockitoBean QueryBus queryBus;

    private static AuthResult authResult(boolean newUser) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        return new AuthResult(UUID.randomUUID(), newUser, new AccessToken("access.jwt.value", now.plusSeconds(900)),
                "refresh-token-value", now.plusSeconds(30L * 24 * 3600));
    }

    @Test
    void captchaEndpointIsPublicUncachedAndSeedsCsrfCookie() {
        when(commandBus.dispatch(any(IssueCaptchaCommand.class)))
                .thenReturn(Mono.just(new CaptchaChallenge("builtin", "cap-1", "data:image/png;base64,AAA", null, 120)));

        client.get().uri("/api/v1/auth/captcha").exchange()
                .expectStatus().isOk()
                .expectHeader().cacheControl(org.springframework.http.CacheControl.noStore())
                .expectCookie().exists("XSRF-TOKEN")
                .expectBody()
                .jsonPath("$.captchaId").isEqualTo("cap-1")
                .jsonPath("$.provider").isEqualTo("builtin");
    }

    @Test
    void csrfEndpointSeedsTokenCookie() {
        client.get().uri("/api/v1/auth/csrf").exchange()
                .expectStatus().isNoContent()
                .expectCookie().exists("XSRF-TOKEN")
                .expectCookie().httpOnly("XSRF-TOKEN", false);
        verifyNoInteractions(commandBus);
    }

    @Test
    void stateChangingCallsRequireCsrfToken() {
        client.post().uri("/api/v1/auth/otp")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"identifier\":\"9876543210\",\"captchaId\":\"c\",\"captchaAnswer\":\"A\"}")
                .exchange()
                .expectStatus().isForbidden();

        verifyNoInteractions(commandBus);
    }

    @Test
    void validatesRequestBody() {
        client.mutateWith(csrf()).post().uri("/api/v1/auth/otp")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"identifier\":\"\",\"captchaAnswer\":\"\"}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("VALIDATION_FAILED")
                .jsonPath("$.errors.identifier").isEqualTo("Enter your email or mobile number.")
                .jsonPath("$.errors.captchaAnswer").exists();
    }

    @Test
    void sendOtpDispatchesCommandWithClientInfo() {
        when(commandBus.dispatch(any(SendOtpCommand.class))).thenReturn(Mono.just(
                new SendOtpCommand.Result("req-1", "PHONE", "+91******3210", 300, 30)));

        client.mutateWith(csrf()).post().uri("/api/v1/auth/otp")
                .header("X-Forwarded-For", "203.0.113.7, 10.0.0.1")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"identifier\":\"9876543210\",\"captchaId\":\"cap-1\",\"captchaAnswer\":\"ab3cd\"}")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.requestId").isEqualTo("req-1")
                .jsonPath("$.maskedIdentifier").isEqualTo("+91******3210");

        ArgumentCaptor<SendOtpCommand> cmd = ArgumentCaptor.forClass(SendOtpCommand.class);
        verify(commandBus).dispatch(cmd.capture());
        assertThat(cmd.getValue().identifier()).isEqualTo("9876543210");
        assertThat(cmd.getValue().client().ipAddress()).isEqualTo("203.0.113.7");
    }

    @Test
    void verifyOtpSetsHardenedCookiesAndKeepsTokensOutOfBody() {
        when(commandBus.dispatch(any(VerifyOtpCommand.class))).thenReturn(Mono.just(authResult(true)));

        client.mutateWith(csrf()).post().uri("/api/v1/auth/otp/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"requestId\":\"req-1\",\"otp\":\"123456\"}")
                .exchange()
                .expectStatus().isOk()
                .expectCookie().httpOnly("lailo_at", true)
                .expectCookie().secure("lailo_at", true)
                .expectCookie().sameSite("lailo_at", "Lax")
                .expectCookie().path("lailo_at", "/")
                .expectCookie().httpOnly("lailo_rt", true)
                .expectCookie().sameSite("lailo_rt", "Strict")
                .expectCookie().path("lailo_rt", "/api/v1/auth")
                .expectBody()
                .jsonPath("$.newUser").isEqualTo(true)
                .jsonPath("$.accessToken").doesNotExist()
                .jsonPath("$.refreshToken").doesNotExist();
    }

    @Test
    void rejectsNonNumericOtp() {
        client.mutateWith(csrf()).post().uri("/api/v1/auth/otp/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"requestId\":\"req-1\",\"otp\":\"12ab56\"}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.errors.otp").isEqualTo("The code is numbers only.");
    }

    @Test
    void businessErrorsUseProblemDetailWithStableCode() {
        when(commandBus.dispatch(any(VerifyOtpCommand.class))).thenReturn(Mono.error(AuthErrors.otpInvalid(3)));

        client.mutateWith(csrf()).post().uri("/api/v1/auth/otp/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"requestId\":\"req-1\",\"otp\":\"000000\"}")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.code").isEqualTo("OTP_INVALID")
                .jsonPath("$.attemptsLeft").isEqualTo(3)
                .jsonPath("$.detail").isEqualTo("Incorrect code. 3 attempts left.");
    }

    @Test
    void refreshReadsTokenFromCookieOnly() {
        when(commandBus.dispatch(any(RefreshSessionCommand.class))).thenReturn(Mono.just(authResult(false)));

        client.mutateWith(csrf()).post().uri("/api/v1/auth/refresh")
                .cookie("lailo_rt", "the-refresh-token")
                .exchange()
                .expectStatus().isOk()
                .expectCookie().exists("lailo_at");

        ArgumentCaptor<RefreshSessionCommand> cmd = ArgumentCaptor.forClass(RefreshSessionCommand.class);
        verify(commandBus).dispatch(cmd.capture());
        assertThat(cmd.getValue().refreshToken()).isEqualTo("the-refresh-token");
    }

    @Test
    void failedRefreshClearsCookies() {
        when(commandBus.dispatch(any(RefreshSessionCommand.class))).thenReturn(Mono.error(AuthErrors.sessionInvalid()));

        client.mutateWith(csrf()).post().uri("/api/v1/auth/refresh")
                .cookie("lailo_rt", "revoked")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectCookie().maxAge("lailo_rt", java.time.Duration.ZERO);
    }

    @Test
    void logoutRevokesAndClearsCookies() {
        when(commandBus.dispatch(any(LogoutCommand.class))).thenReturn(Mono.just(true));

        client.mutateWith(csrf()).post().uri("/api/v1/auth/logout")
                .cookie("lailo_rt", "rt")
                .exchange()
                .expectStatus().isNoContent()
                .expectCookie().maxAge("lailo_at", java.time.Duration.ZERO)
                .expectCookie().maxAge("lailo_rt", java.time.Duration.ZERO);
    }

    @Test
    void profileRequiresAuthentication() {
        client.get().uri("/api/v1/users/me").exchange().expectStatus().isUnauthorized();
        verifyNoInteractions(queryBus);
    }

    @Test
    void profileAcceptsBearerJwt() {
        UUID id = UUID.randomUUID();
        when(queryBus.ask(any(GetCurrentUserQuery.class))).thenReturn(Mono.just(
                new UserProfileView(id.toString(), "rahul@example.com", null, Instant.EPOCH, Instant.EPOCH)));

        client.mutateWith(mockJwt().jwt(j -> j.subject(id.toString())))
                .get().uri("/api/v1/users/me").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.email").isEqualTo("rahul@example.com");
    }

    @Test
    void profileAcceptsSignedAccessTokenCookieAndRejectsTamperedOne() {
        User user = User.register(UUID.randomUUID(), new Identifier(IdentifierType.EMAIL, "a@b.co"), Instant.now());
        String token = jwtTokenService.issue(user, UUID.randomUUID()).value();
        when(queryBus.ask(any(GetCurrentUserQuery.class))).thenReturn(Mono.just(
                new UserProfileView(user.getId().toString(), "a@b.co", null, Instant.EPOCH, Instant.EPOCH)));

        client.get().uri("/api/v1/users/me").cookie("lailo_at", token).exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.id").isEqualTo(user.getId().toString());

        String tampered = token.substring(0, token.length() - 4) + "AAAA";
        client.get().uri("/api/v1/users/me").cookie("lailo_at", tampered).exchange()
                .expectStatus().isUnauthorized();

        client.get().uri("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange()
                .expectStatus().isOk();
    }

    @Test
    void unexpectedErrorsDoNotLeakInternals() {
        when(commandBus.dispatch(any(IssueCaptchaCommand.class)))
                .thenReturn(Mono.error(new IllegalStateException("redis password=secret")));

        client.get().uri("/api/v1/auth/captcha").exchange()
                .expectStatus().isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
                .expectBody()
                .jsonPath("$.code").isEqualTo("INTERNAL_ERROR")
                .jsonPath("$.detail").value(d -> assertThat((String) d).doesNotContain("secret"));
    }

}
