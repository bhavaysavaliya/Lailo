package com.lailo.application.features.auth.application.query;

import com.lailo.application.common.cqrs.QueryHandler;
import com.lailo.application.features.auth.application.AuthErrors;
import com.lailo.application.features.auth.infrastructure.persistence.UserRepository;
import com.lailo.application.features.auth.infrastructure.readmodel.UserProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Reads the MongoDB projection. The projection is eventually consistent (~1s behind), so right after
 * sign-up it may not exist yet: fall back to the write model rather than show an empty profile.
 */
@Component
@RequiredArgsConstructor
public class GetCurrentUserHandler implements QueryHandler<GetCurrentUserQuery, UserProfileView> {

    private final UserProfileRepository profiles;
    private final UserRepository users;

    @Override
    public Mono<UserProfileView> handle(GetCurrentUserQuery q) {
        return profiles.findById(q.userId().toString())
                .map(p -> new UserProfileView(p.getId(), p.getEmail(), p.getPhone(), p.getRegisteredAt(), p.getLastLoginAt()))
                .switchIfEmpty(Mono.defer(() -> users.findById(q.userId())
                        .map(u -> new UserProfileView(u.getId().toString(), u.getEmail(), u.getPhone(),
                                u.getCreatedAt(), u.getLastLoginAt()))))
                .switchIfEmpty(Mono.error(AuthErrors.sessionInvalid()));
    }

    @Override
    public Class<GetCurrentUserQuery> queryType() {
        return GetCurrentUserQuery.class;
    }
}
