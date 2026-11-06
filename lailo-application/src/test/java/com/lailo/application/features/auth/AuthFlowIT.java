package com.lailo.application.features.auth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.lailo.application.features.auth.infrastructure.readmodel.UserProfileDocument;
import com.lailo.application.features.auth.infrastructure.readmodel.UserProfileRepository;
import com.lailo.application.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end sign-in over HTTP against real Postgres, Redis, Kafka and MongoDB:
 * captcha -> OTP -> verify -> profile -> Kafka projection -> refresh rotation -> reuse detection -> logout.
 */
@AutoConfigureWebTestClient
class AuthFlowIT extends IntegrationTest {

    @Autowired WebTestClient client;
    @Autowired ReactiveStringRedisTemplate redis;
    @Autowired UserProfileRepository profiles;
    @Autowired DatabaseClient db;

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Captcha(String captchaId, String image) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record OtpSent(String requestId, String maskedIdentifier, String identifierType) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SignIn(String userId, boolean newUser, Instant accessTokenExpiresAt) {
    }

    /** A browser-like session: remembers cookies and echoes the CSRF token. */
    private final class Browser {
        String xsrf;
        String access;
        String refresh;

        Captcha captcha() {
            EntityExchangeResult<Captcha> res = client.get().uri("/api/v1/auth/captcha").exchange()
                    .expectStatus().isOk().expectBody(Captcha.class).returnResult();
            remember(res);
            assertThat(res.getResponseBody().image()).startsWith("data:image/png;base64,");
            return res.getResponseBody();
        }

        WebTestClient.ResponseSpec post(String uri, Object body) {
            WebTestClient.RequestBodySpec req = client.post().uri(uri)
                    .header("X-XSRF-TOKEN", xsrf)
                    .cookie("XSRF-TOKEN", xsrf);
            if (refresh != null) req.cookie("lailo_rt", refresh);
            if (access != null) req.cookie("lailo_at", access);
            return body == null ? req.exchange() : req.contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange();
        }

        void remember(EntityExchangeResult<?> res) {
            Map<String, java.util.List<ResponseCookie>> cookies = res.getResponseCookies();
            if (cookies.containsKey("XSRF-TOKEN")) xsrf = cookies.get("XSRF-TOKEN").getFirst().getValue();
            if (cookies.containsKey("lailo_at")) access = cookies.get("lailo_at").getFirst().getValue();
            if (cookies.containsKey("lailo_rt")) refresh = cookies.get("lailo_rt").getFirst().getValue();
        }

        OtpSent sendOtp(String identifier) {
            Captcha c = captcha();
            String answer = redis.opsForValue().get("captcha:" + c.captchaId()).block();
            EntityExchangeResult<OtpSent> res = post("/api/v1/auth/otp",
                    Map.of("identifier", identifier, "captchaId", c.captchaId(), "captchaAnswer", answer.toLowerCase()))
                    .expectStatus().isOk().expectBody(OtpSent.class).returnResult();
            return res.getResponseBody();
        }

        SignIn verify(String requestId, String otp) {
            EntityExchangeResult<SignIn> res = post("/api/v1/auth/otp/verify", Map.of("requestId", requestId, "otp", otp))
                    .expectStatus().isOk().expectBody(SignIn.class).returnResult();
            remember(res);
            return res.getResponseBody();
        }
    }


    @Test
    void fullPasswordlessLifecycle() {
        Browser browser = new Browser();
        String email = "user-" + UUID.randomUUID() + "@Example.com";

        // 1. sign up
        OtpSent sent = browser.sendOtp(email);
        assertThat(sent.identifierType()).isEqualTo("EMAIL");
        assertThat(sent.maskedIdentifier()).doesNotContain("user-");
        SignIn first = browser.verify(sent.requestId(), "123456");
        assertThat(first.newUser()).isTrue();
        assertThat(browser.access).isNotBlank();
        assertThat(browser.refresh).isNotBlank();

        // 2. authenticated read (query side)
        client.get().uri("/api/v1/users/me").cookie("lailo_at", browser.access).exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.email").isEqualTo(email.toLowerCase());

        // 3. outbox -> Kafka -> MongoDB projection
        UserProfileDocument projected = awaitProfile(first.userId());
        assertThat(projected.getEmail()).isEqualTo(email.toLowerCase());
        assertThat(projected.getLastLoginAt()).isNotNull();

        // 4. second sign-in with same identifier (different case) is a login, not a new account
        expireResendCooldowns();
        Browser laptop = new Browser();
        SignIn again = laptop.verify(laptop.sendOtp(email.toUpperCase()).requestId(), "123456");
        assertThat(again.newUser()).isFalse();
        assertThat(again.userId()).isEqualTo(first.userId());

        // 5. refresh rotation
        String stolen = browser.refresh;
        EntityExchangeResult<SignIn> refreshed = browser.post("/api/v1/auth/refresh", null)
                .expectStatus().isOk().expectBody(SignIn.class).returnResult();
        browser.remember(refreshed);
        assertThat(browser.refresh).isNotEqualTo(stolen);

        // 6. replaying the rotated token = theft: whole family revoked, legit token dies too
        Browser attacker = new Browser();
        attacker.captcha();
        attacker.refresh = stolen;
        attacker.post("/api/v1/auth/refresh", null).expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.code").isEqualTo("SESSION_INVALID");
        browser.post("/api/v1/auth/refresh", null).expectStatus().isUnauthorized();

        // the other device (separate family) is unaffected; logout revokes it
        laptop.post("/api/v1/auth/logout", null).expectStatus().isNoContent();
        laptop.post("/api/v1/auth/refresh", null).expectStatus().isUnauthorized();

        Long reuseEvents = db.sql("SELECT count(*) FROM outbox_messages WHERE event_type = 'SessionReuseDetected' AND aggregate_id = :id")
                .bind("id", first.userId()).map(r -> r.get(0, Long.class)).one().block();
        assertThat(reuseEvents).isEqualTo(1L);
    }

    @Test
    void phoneSignUpAndWrongOtpHandling() {
        Browser browser = new Browser();
        String phone = uniquePhone();
        OtpSent sent = browser.sendOtp(phone);
        assertThat(sent.identifierType()).isEqualTo("PHONE");
        assertThat(sent.maskedIdentifier()).startsWith("+91").endsWith(phone.substring(6));

        browser.post("/api/v1/auth/otp/verify", Map.of("requestId", sent.requestId(), "otp", "111111"))
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.code").isEqualTo("OTP_INVALID").jsonPath("$.attemptsLeft").isEqualTo(4);

        SignIn signIn = browser.verify(sent.requestId(), "123456");
        assertThat(signIn.newUser()).isTrue();

        // OTP is single use
        browser.post("/api/v1/auth/otp/verify", Map.of("requestId", sent.requestId(), "otp", "123456"))
                .expectStatus().isEqualTo(410)
                .expectBody().jsonPath("$.code").isEqualTo("OTP_EXPIRED");

        String stored = db.sql("SELECT phone FROM users WHERE id = :id").bind("id", UUID.fromString(signIn.userId()))
                .map(r -> r.get(0, String.class)).one().block();
        assertThat(stored).isEqualTo("+91" + phone);
    }

    @Test
    void wrongCaptchaIsRejectedAndCannotBeRetried() {
        Browser browser = new Browser();
        Captcha c = browser.captcha();
        String answer = redis.opsForValue().get("captcha:" + c.captchaId()).block();

        browser.post("/api/v1/auth/otp", Map.of("identifier", uniquePhone(), "captchaId", c.captchaId(), "captchaAnswer", "WRONG"))
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("CAPTCHA_INVALID");

        browser.post("/api/v1/auth/otp", Map.of("identifier", uniquePhone(), "captchaId", c.captchaId(), "captchaAnswer", answer))
                .expectStatus().isBadRequest();
    }

    @Test
    void resendIsThrottled() {
        Browser browser = new Browser();
        String phone = uniquePhone();
        browser.sendOtp(phone);

        Captcha c = browser.captcha();
        String answer = redis.opsForValue().get("captcha:" + c.captchaId()).block();
        browser.post("/api/v1/auth/otp", Map.of("identifier", phone, "captchaId", c.captchaId(), "captchaAnswer", answer))
                .expectStatus().isEqualTo(429)
                .expectBody().jsonPath("$.code").isEqualTo("OTP_RESEND_COOLDOWN");
    }

    @Test
    void protectedEndpointsRejectAnonymousAndCsrfLessCalls() {
        client.get().uri("/api/v1/users/me").exchange().expectStatus().isUnauthorized();
        client.post().uri("/api/v1/auth/otp").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("identifier", "9876543210", "captchaAnswer", "x"))
                .exchange().expectStatus().isForbidden();
    }

    /** Simulates the 30s resend cooldown elapsing. */
    private void expireResendCooldowns() {
        redis.scan(org.springframework.data.redis.core.ScanOptions.scanOptions().match("rl:cd:otp:*").build())
                .flatMap(redis::delete).blockLast();
    }

    private UserProfileDocument awaitProfile(String userId) {
        Instant deadline = Instant.now().plusSeconds(20);
        while (Instant.now().isBefore(deadline)) {
            UserProfileDocument doc = profiles.findById(userId).block(Duration.ofSeconds(2));
            if (doc != null && doc.getLastLoginAt() != null) {
                return doc;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
        throw new AssertionError("Read model for " + userId + " was not projected within 20s");
    }
}
