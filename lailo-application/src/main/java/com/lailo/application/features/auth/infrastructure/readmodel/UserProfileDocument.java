package com.lailo.application.features.auth.infrastructure.readmodel;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/** Read-model projection (MongoDB) built from user events. Denormalised for fast profile reads. */
@Document("user_profiles")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserProfileDocument {
    @Id
    private String id;
    private String email;
    private String phone;
    private Instant registeredAt;
    private Instant lastLoginAt;
    private String lastLoginMethod;
}
