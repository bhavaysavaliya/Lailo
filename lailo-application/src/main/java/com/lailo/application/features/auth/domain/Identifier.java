package com.lailo.application.features.auth.domain;

import com.lailo.application.common.web.ApiException;
import org.springframework.http.HttpStatus;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Value object for "email or mobile number" login input, normalised so the same person
 * always maps to the same account: emails are lower-cased, phones become E.164 (+919876543210).
 */
public record Identifier(IdentifierType type, String value) {

    private static final Pattern EMAIL = Pattern.compile(
            "^[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+$");
    private static final Pattern E164 = Pattern.compile("^\\+[1-9]\\d{7,14}$");
    /** Indian mobile numbers: 10 digits starting 6-9. */
    private static final Pattern IN_MOBILE = Pattern.compile("^[6-9]\\d{9}$");
    private static final Pattern PHONE_SEPARATORS = Pattern.compile("[\\s\\-().]");

    /**
     * Validates and normalises what the user typed. Input containing {@code @} is an email (lower-cased); anything
     * else is a phone number, accepted with spaces, dashes, a trunk {@code 0}, a {@code 00} or {@code +} prefix, and
     * converted to E.164.
     *
     * @param raw user input
     * @param defaultCountryCode country code to assume when none is given, e.g. {@code 91}
     * @return the normalised identifier
     * @throws ApiException {@code INVALID_IDENTIFIER} when the input is neither a valid email nor a valid number
     */
    public static Identifier parse(String raw, String defaultCountryCode) {
        if (raw == null || raw.isBlank()) {
            throw invalid();
        }
        String input = raw.trim();
        if (input.contains("@")) {
            if (input.length() > 254 || !EMAIL.matcher(input).matches()) {
                throw invalid();
            }
            return new Identifier(IdentifierType.EMAIL, input.toLowerCase(Locale.ROOT));
        }
        String digits = PHONE_SEPARATORS.matcher(input).replaceAll("");
        if (digits.startsWith("00")) {
            digits = "+" + digits.substring(2);
        }
        if (!digits.startsWith("+")) {
            if (digits.length() == 11 && digits.startsWith("0")) {
                digits = digits.substring(1); // trunk prefix 0
            }
            if (digits.length() == 12 && digits.startsWith(defaultCountryCode)) {
                digits = digits.substring(defaultCountryCode.length());
            }
            if ("91".equals(defaultCountryCode) && !IN_MOBILE.matcher(digits).matches()) {
                throw invalid();
            }
            digits = "+" + defaultCountryCode + digits;
        }
        if (!E164.matcher(digits).matches()) {
            throw invalid();
        }
        return new Identifier(IdentifierType.PHONE, digits);
    }

    /** Safe for logs and UI: r****@gmail.com, +91******3210. */
    public String masked() {
        if (type == IdentifierType.EMAIL) {
            int at = value.indexOf('@');
            String local = value.substring(0, at);
            String visible = local.substring(0, Math.min(1, local.length()));
            return visible + "*".repeat(Math.max(3, local.length() - 1)) + value.substring(at);
        }
        int keepStart = value.startsWith("+91") ? 3 : Math.min(3, value.length());
        int keepEnd = 4;
        return value.substring(0, keepStart)
                + "*".repeat(Math.max(0, value.length() - keepStart - keepEnd))
                + value.substring(value.length() - keepEnd);
    }

    /**
     * Uses the masked value so identifiers never leak into logs.
     *
     * @return a log-safe representation
     */
    @Override
    public String toString() {
        return "Identifier[" + type + ", " + masked() + "]";
    }

    /**
     * Builds the single error used for every invalid input.
     *
     * @return 400 {@code INVALID_IDENTIFIER}
     */
    private static ApiException invalid() {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_IDENTIFIER",
                "Enter a valid email address or 10-digit mobile number.");
    }
}
