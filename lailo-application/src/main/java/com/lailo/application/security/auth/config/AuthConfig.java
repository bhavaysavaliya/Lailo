package com.lailo.application.security.auth.config;

import com.lailo.application.features.auth.config.AuthProperties;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.security.oauth2.server.resource.web.server.authentication.ServerBearerTokenAuthenticationConverter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.HttpStatusServerEntryPoint;
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter;
import org.springframework.security.web.server.authorization.HttpStatusServerAccessDeniedHandler;
import org.springframework.security.web.server.csrf.CookieServerCsrfTokenRepository;
import org.springframework.security.web.server.csrf.CsrfToken;
import org.springframework.security.web.server.csrf.ServerCsrfTokenRequestAttributeHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Stateless API security:
 * <ul>
 *   <li>Access JWT read from the HttpOnly cookie (browser) or {@code Authorization: Bearer} (mobile / service).</li>
 *   <li>CSRF: double-submit cookie {@code XSRF-TOKEN} echoed in {@code X-XSRF-TOKEN} - required because auth rides on cookies.</li>
 *   <li>Deny by default: every {@code /api/**} route needs a valid token unless listed as public.</li>
 * </ul>
 */
@Configuration
@EnableWebFluxSecurity
public class AuthConfig {

    /**
     * Builds the WebFlux security filter chain: CORS, cookie-based CSRF, stateless JWT authentication and the
     * public/protected route rules. Form login, HTTP basic and Spring's logout are disabled.
     *
     * @param http the security builder
     * @param jwtDecoder verifies access tokens
     * @param props auth settings (cookie names, secure flag)
     * @param corsConfigurationSource CORS rules for {@code /api/**}
     * @return the configured filter chain
     */
    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http,
                                                         ReactiveJwtDecoder jwtDecoder,
                                                         AuthProperties props,
                                                         CorsConfigurationSource corsConfigurationSource) {
        CookieServerCsrfTokenRepository csrfRepo = CookieServerCsrfTokenRepository.withHttpOnlyFalse();
        csrfRepo.setCookieCustomizer(c -> c.sameSite("Strict").secure(props.cookies().secure()).path("/"));

        return http
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfRepo)
                        // plain (non-XOR) token so a SPA can echo the cookie value as-is
                        .csrfTokenRequestHandler(new ServerCsrfTokenRequestAttributeHandler()))
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .authorizeExchange(ex -> ex
                        .pathMatchers("/api/v1/auth/**").permitAll()
                        .pathMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .pathMatchers(HttpMethod.GET, "/product/**").permitAll()
                        .pathMatchers("/api/**").authenticated()
                        .anyExchange().permitAll())
                .oauth2ResourceServer(rs -> rs
                        .bearerTokenConverter(cookieOrHeaderTokenConverter(props.cookies().accessName()))
                        .jwt(jwt -> jwt.jwtDecoder(jwtDecoder))
                        .authenticationEntryPoint(new HttpStatusServerEntryPoint(HttpStatus.UNAUTHORIZED)))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(new HttpStatusServerEntryPoint(HttpStatus.UNAUTHORIZED))
                        .accessDeniedHandler(new HttpStatusServerAccessDeniedHandler(HttpStatus.FORBIDDEN)))
                .addFilterAfter(csrfCookieMaterializer(), SecurityWebFiltersOrder.CSRF)
                .build();
    }

    /** The CSRF token is lazy in WebFlux; subscribing forces the XSRF-TOKEN cookie onto every response. */
    private static WebFilter csrfCookieMaterializer() {
        return (exchange, chain) -> {
            Mono<CsrfToken> token = exchange.getAttribute(CsrfToken.class.getName());
            return (token == null ? Mono.<CsrfToken>empty() : token).then(chain.filter(exchange));
        };
    }

    /**
     * Reads the bearer token from the {@code Authorization} header if present, otherwise from the access-token
     * cookie, so browsers (cookie) and other clients (header) are both supported.
     *
     * @param cookieName name of the access-token cookie
     * @return a converter that emits a bearer token, or completes empty when the request carries none
     */
    private static ServerAuthenticationConverter cookieOrHeaderTokenConverter(String cookieName) {
        ServerBearerTokenAuthenticationConverter header = new ServerBearerTokenAuthenticationConverter();
        return exchange -> {
            if (exchange.getRequest().getHeaders().containsHeader(HttpHeaders.AUTHORIZATION)) {
                return header.convert(exchange);
            }
            HttpCookie cookie = exchange.getRequest().getCookies().getFirst(cookieName);
            if (cookie == null || cookie.getValue().isBlank()) {
                return Mono.empty();
            }
            return Mono.just(new BearerTokenAuthenticationToken(cookie.getValue()));
        };
    }

    /**
     * Derives the HMAC-SHA256 signing key from the configured JWT secret.
     *
     * @param props auth settings holding the secret
     * @return the shared signing key
     */
    @Bean
    SecretKey jwtSigningKey(AuthProperties props) {
        return new SecretKeySpec(props.jwt().secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    /**
     * Creates the encoder used to sign access tokens.
     *
     * @param jwtSigningKey HMAC key
     * @return a Nimbus JWT encoder
     */
    @Bean
    JwtEncoder jwtEncoder(SecretKey jwtSigningKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSigningKey));
    }

    /**
     * Creates the decoder that verifies HS256 signatures, expiry and the expected issuer.
     *
     * @param jwtSigningKey HMAC key
     * @param props auth settings holding the issuer
     * @return a reactive JWT decoder
     */
    @Bean
    ReactiveJwtDecoder jwtDecoder(SecretKey jwtSigningKey, AuthProperties props) {
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withSecretKey(jwtSigningKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(props.jwt().issuer())));
        return decoder;
    }

    /**
     * The Next.js app proxies /api to this service (same origin), so CORS is only needed when a browser calls
     * the API directly - e.g. a separately hosted frontend.
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource(
            @Value("${lailo.web.allowed-origins:http://localhost:3000}") List<String> allowedOrigins) {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOrigins(allowedOrigins);
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("Content-Type", "X-XSRF-TOKEN", "Authorization"));
        cfg.setAllowCredentials(true);
        cfg.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cfg);
        return source;
    }
}
