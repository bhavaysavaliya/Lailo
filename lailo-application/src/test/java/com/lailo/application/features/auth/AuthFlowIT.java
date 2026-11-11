package com.lailo.application.features.auth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.lailo.application.features.auth.infrastructure.readmodel.UserProfileDocument;
import com.lailo.application.features.auth.infrastructure.readmodel.UserProfileRepository;
import com.lailo.application.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
class AuthFlowIT extends IntegrationTest {

    @Autowired WebTestClient client;
    @Autowired ReactiveStringRedisTemplate redis;
    @Autowired UserProfileRepository profiles;
    @Autowired DatabaseClient db;

    /**
     * Captcha response fields used by the test.
     *
     * @param captchaId challenge id
     * @param image PNG data URI
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Captcha(String captchaId, String image) {
    }

    /**
     * Send-OTP response fields used by the test.
     *
     * @param requestId OTP request id
     * @param maskedIdentifier destination safe to display
     * @param identifierType {@code EMAIL} or {@code PHONE}
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record OtpSent(String requestId, String maskedIdentifier, String identifierType) {
    }

    /**
     * Sign-in response fields used by the test.
     *
     * @param userId user id
     * @param newUser whether the account was just created
     * @param accessTokenExpiresAt access-token expiry
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record SignIn(String userId, boolean newUser, Instant accessTokenExpiresAt) {
    }

    /** A browser-like session: remembers cookies and echoes the CSRF token. */
    private final class Browser {
        String xsrf;
        String access;
        String refresh;

        /**
         * Fetches a captcha and remembers the cookies it set.
         *
         * @return the challenge
         */
        Captcha captcha() {
            EntityExchangeResult<Captcha> res = client.get().uri("/api/v1/auth/captcha").exchange()
                    .expectStatus().isOk().expectBody(Captcha.class).returnResult();
            remember(res);
            assertThat(res.getResponseBody().image()).startsWith("data:image/png;base64,");
            return res.getResponseBody();
        }

        /**
         * Sends a POST with the CSRF header and whatever auth cookies this browser holds.
         *
         * @param uri request path
         * @param body JSON body, or {@code null} for none
         * @return the response spec for assertions
         */
        WebTestClient.ResponseSpec post(String uri, Object body) {
            WebTestClient.RequestBodySpec req = client.post().uri(uri)
                    .header("X-XSRF-TOKEN", xsrf)
                    .cookie("XSRF-TOKEN", xsrf);
            if (refresh != null) req.cookie("lailo_rt", refresh);
            if (access != null) req.cookie("lailo_at", access);
            return body == null ? req.exchange() : req.contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange();
        }

        /**
         * Stores the XSRF, access and refresh cookies from a response, like a browser cookie jar.
         *
         * @param res the exchange result
         */
        void remember(EntityExchangeResult<?> res) {
            Map<String, java.util.List<ResponseCookie>> cookies = res.getResponseCookies();
            if (cookies.containsKey("XSRF-TOKEN")) xsrf = cookies.get("XSRF-TOKEN").getFirst().getValue();
            if (cookies.containsKey("lailo_at")) access = cookies.get("lailo_at").getFirst().getValue();
            if (cookies.containsKey("lailo_rt")) refresh = cookies.get("lailo_rt").getFirst().getValue();
        }

        /**
         * Solves a captcha (answer read from Redis) and requests an OTP.
         *
         * @param identifier email or phone to send to
         * @return the send result
         */
        OtpSent sendOtp(String identifier) {
            Captcha c = captcha();
            String answer = redis.opsForValue().get("captcha:" + c.captchaId()).block();
            EntityExchangeResult<OtpSent> res = post("/api/v1/auth/otp",
                    Map.of("identifier", identifier, "captchaId", c.captchaId(), "captchaAnswer", answer.toLowerCase()))
                    .expectStatus().isOk().expectBody(OtpSent.class).returnResult();
            return res.getResponseBody();
        }

        /**
         * Verifies an OTP and remembers the auth cookies.
         *
         * @param requestId OTP request id
         * @param otp code to submit
         * @return the sign-in result
         */
        SignIn verify(String requestId, String otp) {
            EntityExchangeResult<SignIn> res = post("/api/v1/auth/otp/verify", Map.of("requestId", requestId, "otp", otp))
                    .expectStatus().isOk().expectBody(SignIn.class).returnResult();
            remember(res);
            return res.getResponseBody();
        }
    }


    /**
     * The whole passwordless lifecycle works across HTTP, Postgres, Redis, Kafka and MongoDB.
     * <ul>
     *   <li><b>Given</b> a new email address</li>
     *   <li><b>When</b> the user signs up, reads their profile, signs in again from a second device with different letter case, refreshes, has the old refresh token replayed by an attacker, and signs out on the second device</li>
     *   <li><b>Then</b> a new account is created once; the profile is projected to MongoDB via Kafka; the second sign-in reuses the account; rotation issues a new token; the replay revokes the first device's family and records one {@code SessionReuseDetected}; logout revokes the second device</li>
     * </ul>
     */
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

    /**
     * A phone number signs up after one wrong code, and the code is single use.
     * <ul>
     *   <li><b>Given</b> a new 10-digit mobile number</li>
     *   <li><b>When</b> a wrong code is submitted, then the right one, then the right one again</li>
     *   <li><b>Then</b> the first gets 401 with 4 attempts left, the second creates the account (stored as {@code +91...}), the third gets 410 {@code OTP_EXPIRED}</li>
     * </ul>
     */
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

    /**
     * A wrong captcha is rejected and the captcha cannot be retried.
     * <ul>
     *   <li><b>Given</b> a fresh captcha</li>
     *   <li><b>When</b> a wrong answer is sent, then the correct answer for the same captcha</li>
     *   <li><b>Then</b> both requests get 400</li>
     * </ul>
     */
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

    /**
     * Resending inside the cooldown is throttled.
     * <ul>
     *   <li><b>Given</b> an OTP was just sent to a phone number</li>
     *   <li><b>When</b> another OTP is requested for the same number with a new captcha</li>
     *   <li><b>Then</b> status is 429 with {@code OTP_RESEND_COOLDOWN}</li>
     * </ul>
     */
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

    /**
     * Protected and state-changing endpoints reject unauthenticated or CSRF-less calls.
     * <ul>
     *   <li><b>Given</b> no cookies and no CSRF token</li>
     *   <li><b>When</b> {@code GET /api/v1/users/me} and {@code POST /api/v1/auth/otp} are called</li>
     *   <li><b>Then</b> they get 401 and 403 respectively</li>
     * </ul>
     */
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

    /**
     * Polls MongoDB until the Kafka projection has written the user's profile with a login time.
     *
     * @param userId user to wait for
     * @return the projected document
     * @throws AssertionError if it does not appear within 20 seconds
     */
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
