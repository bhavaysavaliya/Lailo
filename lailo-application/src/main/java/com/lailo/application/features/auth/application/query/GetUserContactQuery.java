package com.lailo.application.features.auth.application.query;

import com.lailo.application.common.cqrs.Query;

import java.util.UUID;

/**
 * Reads a user's notification destinations. Unlike {@link GetCurrentUserQuery} this is not serving a
 * signed-in caller: it is how a background consumer (order notifications, payment receipts) finds out where
 * to send something.
 *
 * @param userId the account to look up
 */
public record GetUserContactQuery(UUID userId) implements Query<UserContact> {
}
