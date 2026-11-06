package com.lailo.application.features.auth.infrastructure.otp;

public interface OtpGenerator {
    String generate(int length);
}
