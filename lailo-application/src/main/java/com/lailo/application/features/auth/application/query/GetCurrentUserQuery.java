package com.lailo.application.features.auth.application.query;

import com.lailo.application.common.cqrs.Query;

import java.util.UUID;

public record GetCurrentUserQuery(UUID userId) implements Query<UserProfileView> {
}
