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

    /** Creates the service pointed at the WireMock server with test site key and secret. */
    @BeforeEach
    void setUp() {
        AuthProperties base = TestAuthProperties.create();
        AuthProperties props = new AuthProperties(base.otp(),
                new AuthProperties.Captcha("turnstile", Duration.ofMinutes(2), 5, "site-key", "server-secret",
                        cloudflare.baseUrl()),
                base.jwt(), base.session(), base.cookies(), base.rateLimit(), "IN", "91");
        service = new TurnstileCaptchaService(props, WebClient.builder());
    }

    /**
     * A token Cloudflare accepts is verified, and the request matches the siteverify contract.
     * <ul>
     *   <li><b>Given</b> siteverify answers {@code success: true}</li>
     *   <li><b>When</b> a widget token is verified with a client IP</li>
     *   <li><b>Then</b> the result is {@code true} and the form-encoded request carried secret, response and remoteip</li>
     * </ul>
     */
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

    /**
     * A token Cloudflare rejects is not verified.
     * <ul>
     *   <li><b>Given</b> siteverify answers {@code success: false} with an error code</li>
     *   <li><b>When</b> the token is verified</li>
     *   <li><b>Then</b> the result is {@code false}</li>
     * </ul>
     */
    @Test
    void rejectsTokenWhenCloudflareDenies() {
        cloudflare.stubFor(post(urlEqualTo(PATH)).willReturn(okJson("""
                {"success": false, "error-codes": ["invalid-input-response"]}
                """)));

        StepVerifier.create(service.verify(null, "bad", "203.0.113.7")).expectNext(false).verifyComplete();
    }

    /**
     * A Cloudflare outage fails closed.
     * <ul>
     *   <li><b>Given</b> siteverify returns HTTP 503</li>
     *   <li><b>When</b> a token is verified</li>
     *   <li><b>Then</b> the result is {@code false}, not an error</li>
     * </ul>
     */
    @Test
    void failsClosedOnServerError() {
        cloudflare.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(503)));

        StepVerifier.create(service.verify(null, "token", null)).expectNext(false).verifyComplete();
    }

    /**
     * A dropped connection fails closed.
     * <ul>
     *   <li><b>Given</b> siteverify resets the connection</li>
     *   <li><b>When</b> a token is verified</li>
     *   <li><b>Then</b> the result is {@code false}</li>
     * </ul>
     */
    @Test
    void failsClosedOnConnectionReset() {
        cloudflare.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        StepVerifier.create(service.verify(null, "token", null)).expectNext(false).verifyComplete();
    }

    /**
     * A slow Cloudflare response fails closed.
     * <ul>
     *   <li><b>Given</b> siteverify answers after 6 s, beyond the 5 s timeout</li>
     *   <li><b>When</b> a token is verified</li>
     *   <li><b>Then</b> the result is {@code false}</li>
     * </ul>
     */
    @Test
    void failsClosedWhenCloudflareIsTooSlow() {
        cloudflare.stubFor(post(urlEqualTo(PATH)).willReturn(okJson("{\"success\": true}").withFixedDelay(6_000)));

        StepVerifier.create(service.verify(null, "token", null)).expectNext(false).verifyComplete();
    }

    /**
     * A blank token is rejected without calling Cloudflare.
     * <ul>
     *   <li><b>Given</b> a blank token</li>
     *   <li><b>When</b> it is verified</li>
     *   <li><b>Then</b> the result is {@code false} and WireMock received no request</li>
     * </ul>
     */
    @Test
    void skipsNetworkCallForBlankToken() {
        StepVerifier.create(service.verify(null, " ", null)).expectNext(false).verifyComplete();

        assertThat(cloudflare.getAllServeEvents()).isEmpty();
    }

    /**
     * The challenge exposes what the web widget needs.
     * <ul>
     *   <li><b>Given</b> the Turnstile provider with a site key</li>
     *   <li><b>When</b> a challenge is issued</li>
     *   <li><b>Then</b> it names provider {@code turnstile} and carries the site key</li>
     * </ul>
     */
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
