package com.lailo.application.features.auth.infrastructure.otp;

/** Strategy for producing one-time codes. */
public interface OtpGenerator {
    /**
     * Produces a numeric code.
     *
     * @param length number of digits
     * @return the code
     */
    String generate(int length);
}
