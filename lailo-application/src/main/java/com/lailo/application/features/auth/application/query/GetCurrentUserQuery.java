package com.lailo.application.features.auth.application.query;

import com.lailo.application.common.cqrs.Query;

import java.util.UUID;

/**
 * Query for the signed-in user's profile.
 *
 * @param userId subject of the verified access token
 */
public record GetCurrentUserQuery(UUID userId) implements Query<UserProfileView> {
}
