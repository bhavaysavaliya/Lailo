package com.lailo.application.common.web;

import org.springframework.http.server.reactive.ServerHttpRequest;

import java.net.InetSocketAddress;
import java.util.Optional;

/**
 * Caller metadata used for rate limiting, session auditing and fraud signals.
 * Behind a trusted proxy (the Next.js BFF / load balancer) the first X-Forwarded-For hop is the client.
 * Enable {@code server.forward-headers-strategy=native} only when such a proxy strips spoofed headers.
 */
public record ClientInfo(String ipAddress, String userAgent) {

    private static final int MAX_UA = 512;

    public static ClientInfo from(ServerHttpRequest request) {
        String ip = Optional.ofNullable(request.getHeaders().getFirst("X-Forwarded-For"))
                .map(h -> h.split(",")[0].trim())
                .filter(s -> !s.isBlank())
                .orElseGet(() -> Optional.ofNullable(request.getRemoteAddress())
                        .map(InetSocketAddress::getAddress)
                        .map(a -> a.getHostAddress())
                        .orElse("unknown"));
        String ua = Optional.ofNullable(request.getHeaders().getFirst("User-Agent")).orElse("unknown");
        return new ClientInfo(ip, ua.length() > MAX_UA ? ua.substring(0, MAX_UA) : ua);
    }
}
