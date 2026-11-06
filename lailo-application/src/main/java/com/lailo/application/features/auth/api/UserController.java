package com.lailo.application.features.auth.api;

import com.lailo.application.common.cqrs.QueryBus;
import com.lailo.application.features.auth.application.query.GetCurrentUserQuery;
import com.lailo.application.features.auth.application.query.UserProfileView;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final QueryBus queryBus;

    @GetMapping("/me")
    public Mono<UserProfileView> me(@AuthenticationPrincipal Jwt jwt) {
        return queryBus.ask(new GetCurrentUserQuery(UUID.fromString(jwt.getSubject())));
    }
}
