package com.lailo.application.features.order.domain;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Human-readable order reference, e.g. {@code LAILO-20261111-K7M2QX}.
 *
 * <p>The order's primary key stays a UUID; this is the string a shopper reads out on a support call, so it
 * is short, unambiguous (no {@code 0}/{@code O} or {@code 1}/{@code I}) and carries the date. The random
 * suffix - not a counter - keeps daily volume private and needs no coordination between instances.</p>
 */
public final class OrderNumber {

    /** Alphabet without characters that are misread or mistyped over the phone. */
    private static final char[] ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();
    private static final int SUFFIX_LENGTH = 6;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    /** Not instantiable: static factory holder. */
    private OrderNumber() {
    }

    /**
     * Generates a reference for an order placed now.
     *
     * @param placedAt when the order was placed
     * @param random secure random source
     * @return the reference; a collision is rejected by the unique index and retried by the caller
     */
    public static String generate(Instant placedAt, SecureRandom random) {
        StringBuilder suffix = new StringBuilder(SUFFIX_LENGTH);
        for (int i = 0; i < SUFFIX_LENGTH; i++) {
            suffix.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        }
        return "LAILO-" + DAY.format(placedAt) + "-" + suffix;
    }
}
