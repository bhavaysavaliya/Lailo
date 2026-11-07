package com.lailo.application.features.auth.infrastructure.readmodel;

import org.springframework.data.mongodb.repository.ReactiveMongoRepository;

/** Reactive Mongo repository for the {@code user_profiles} read model. */
public interface UserProfileRepository extends ReactiveMongoRepository<UserProfileDocument, String> {
}
