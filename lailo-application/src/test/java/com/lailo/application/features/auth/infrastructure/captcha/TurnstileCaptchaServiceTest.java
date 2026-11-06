package com.lailo.application.features.auth.infrastructure.captcha;

import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.lailo.application.features.auth.config.AuthProperties;
import com.lailo.application.support.TestAuthProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/** Contract test against a WireMock stand-in for Cloudflare's siteverify API. */
class TurnstileCaptchaServiceTest {

    private static final String PATH = "/turnstile/v0/siteverify";

    @RegisterExtension
    static WireMockExtension cloudflare = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private TurnstileCaptchaService service;

    @BeforeEach
    void setUp() {
        AuthProperties base = TestAuthProperties.create();
        AuthProperties props = new AuthProperties(base.otp(),
                new AuthProperties.Captcha("turnstile", Duration.ofMinutes(2), 5, "site-key", "server-secret",
                        cloudflare.baseUrl()),
                base.jwt(), base.session(), base.cookies(), base.rateLimit(), "IN", "91");
        service = new TurnstileCaptchaService(props, WebClient.builder());
    }

    @Test
    void acceptsTokenWhenCloudflareConfirms() {
        cloudflare.stubFor(post(urlEqualTo(PATH)).willReturn(okJson("""
                {"success": true, "challenge_ts": "2026-11-06T10:00:00Z", "hostname": "lailo.in", "error-codes": []}
                """)));

        StepVerifier.create(service.verify(null, "widget-token", "203.0.113.7")).expectNext(true).verifyComplete();

        cloudflare.verify(postRequestedFor(urlEqualTo(PATH))
                .withHeader("Content-Type", containing("application/x-www-form-urlencoded"))
                .withRequestBody(containing("secret=server-secret"))
                .withRequestBody(containing("response=widget-token"))
                .withRequestBody(containing("remoteip=203.0.113.7")));
    }

    @Test
    void rejectsTokenWhenCloudflareDenies() {
        cloudflare.stubFor(post(urlEqualTo(PATH)).willReturn(okJson("""
                {"success": false, "error-codes": ["invalid-input-response"]}
                """)));

        StepVerifier.create(service.verify(null, "bad", "203.0.113.7")).expectNext(false).verifyComplete();
    }

    @Test
    void failsClosedOnServerError() {
        cloudflare.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(503)));

        StepVerifier.create(service.verify(null, "token", null)).expectNext(false).verifyComplete();
    }

    @Test
    void failsClosedOnConnectionReset() {
        cloudflare.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        StepVerifier.create(service.verify(null, "token", null)).expectNext(false).verifyComplete();
    }

    @Test
    void failsClosedWhenCloudflareIsTooSlow() {
        cloudflare.stubFor(post(urlEqualTo(PATH)).willReturn(okJson("{\"success\": true}").withFixedDelay(6_000)));

        StepVerifier.create(service.verify(null, "token", null)).expectNext(false).verifyComplete();
    }

    @Test
    void skipsNetworkCallForBlankToken() {
        StepVerifier.create(service.verify(null, " ", null)).expectNext(false).verifyComplete();

        assertThat(cloudflare.getAllServeEvents()).isEmpty();
    }

    @Test
    void exposesSiteKeyForTheWidget() {
        StepVerifier.create(service.issue())
                .assertNext(c -> {
                    assertThat(c.provider()).isEqualTo("turnstile");
                    assertThat(c.siteKey()).isEqualTo("site-key");
                })
                .verifyComplete();
    }
}
