package com.lailo.application.features.auth.infrastructure.persistence;

import com.lailo.application.features.auth.domain.User;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** R2DBC repository for {@link com.lailo.application.features.auth.domain.User} (table {@code users}). */
public interface UserRepository extends ReactiveCrudRepository<User, UUID> {

    /**
     * Finds a user by normalised email.
     *
     * @param email lower-case email
     * @return the user, or empty
     */
    Mono<User> findByEmail(String email);

    /**
     * Finds a user by normalised phone.
     *
     * @param phone E.164 phone number
     * @return the user, or empty
     */
    Mono<User> findByPhone(String phone);
}
