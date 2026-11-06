package com.lailo.application.features.auth.infrastructure.persistence;

import com.lailo.application.features.auth.domain.User;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface UserRepository extends ReactiveCrudRepository<User, UUID> {

    Mono<User> findByEmail(String email);

    Mono<User> findByPhone(String phone);
}
