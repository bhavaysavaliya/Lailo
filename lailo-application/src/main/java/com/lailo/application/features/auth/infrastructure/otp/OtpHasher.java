package com.lailo.application.features.auth.infrastructure.otp;

import com.lailo.application.features.auth.config.AuthProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Keyed HMAC-SHA256 over (requestId, code). A 6-digit space is trivially brute-forced if stored
 * with a plain hash; the server-side key means a Redis dump alone reveals nothing.
 */
@Component
public class OtpHasher {

    private final SecretKeySpec key;

    /**
     * Creates the hasher from the configured secret.
     *
     * @param props OTP settings holding {@code hashSecret}
     */
    public OtpHasher(AuthProperties props) {
        this.key = new SecretKeySpec(props.otp().hashSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    /**
     * Computes the keyed hash of a code for one request.
     *
     * @param requestId OTP request id, so equal codes in different requests hash differently
     * @param code the plain code
     * @return lowercase hex HMAC-SHA256
     */
    public String hash(String requestId, String code) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal((requestId + ':' + code).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Checks a candidate code in constant time.
     *
     * @param requestId OTP request id
     * @param code candidate code
     * @param expectedHash stored hash
     * @return {@code true} if the code matches
     */
    public boolean matches(String requestId, String code, String expectedHash) {
        return MessageDigest.isEqual(hash(requestId, code).getBytes(StandardCharsets.US_ASCII),
                expectedHash.getBytes(StandardCharsets.US_ASCII));
    }
}
