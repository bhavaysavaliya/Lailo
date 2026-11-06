package com.lailo.application.features.auth.infrastructure.readmodel;

import org.springframework.data.mongodb.repository.ReactiveMongoRepository;

public interface UserProfileRepository extends ReactiveMongoRepository<UserProfileDocument, String> {
}
