package com.lailo.application.features.auth.infrastructure.redis;

import com.lailo.application.features.auth.domain.Identifier;

/** Pending OTP, stored in Redis under an opaque request id. Holds only a keyed hash of the code. */
public record OtpChallenge(String requestId, Identifier identifier, String codeHash, int attempts) {
}
