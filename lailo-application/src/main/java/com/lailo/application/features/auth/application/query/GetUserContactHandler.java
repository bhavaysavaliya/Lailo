package com.lailo.application.features.auth.application.query;

import com.lailo.application.common.cqrs.QueryHandler;
import com.lailo.application.features.auth.application.AuthErrors;
import com.lailo.application.features.auth.infrastructure.persistence.UserRepository;
import com.lailo.application.features.auth.infrastructure.readmodel.UserProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Serves notification destinations from the MongoDB profile, falling back to PostgreSQL. The fallback
 * matters more here than for a screen: an order placed seconds after sign-up must still be confirmed by
 * email, and the projection may not have caught up.
 */
@Component
@RequiredArgsConstructor
public class GetUserContactHandler implements QueryHandler<GetUserContactQuery, UserContact> {

    private final UserProfileRepository profiles;
    private final UserRepository users;

    /**
     * Looks up where to reach the user.
     *
     * @param q the query
     * @return the contact, or {@code SESSION_INVALID} when the account no longer exists
     */
    @Override
    public Mono<UserContact> handle(GetUserContactQuery q) {
        return profiles.findById(q.userId().toString())
                .map(p -> new UserContact(p.getId(), p.getEmail(), p.getPhone()))
                .switchIfEmpty(Mono.defer(() -> users.findById(q.userId())
                        .map(u -> new UserContact(u.getId().toString(), u.getEmail(), u.getPhone()))))
                .switchIfEmpty(Mono.error(AuthErrors.sessionInvalid()));
    }

    /**
     * Serves {@link GetUserContactQuery}.
     *
     * @return the query class
     */
    @Override
    public Class<GetUserContactQuery> queryType() {
        return GetUserContactQuery.class;
    }
}
