package com.lailo.application.features.auth.domain;

import com.lailo.application.common.web.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests for {@link Identifier}: parsing, normalisation and masking of emails and phone numbers. */
class IdentifierTest {

    /**
     * Phone numbers in common Indian and international formats normalise to E.164.
     * <ul>
     *   <li><b>Given</b> a number with spaces, dashes, a trunk {@code 0}, a {@code 0091}/{@code 91} prefix or an explicit {@code +} code</li>
     *   <li><b>When</b> it is parsed with default country code {@code 91}</li>
     *   <li><b>Then</b> the type is {@code PHONE} and the value is the expected E.164 string</li>
     * </ul>
     *
     * @param raw user input
     * @param expected normalised number
     */
    @ParameterizedTest(name = "\"{0}\" -> {1}")
    @CsvSource({
            "9876543210,        +919876543210",
            "98765 43210,       +919876543210",
            "+91 98765-43210,   +919876543210",
            "919876543210,      +919876543210",
            "09876543210,       +919876543210",
            "0091 9876543210,   +919876543210",
            "+14155552671,      +14155552671"
    })
    void normalisesPhoneNumbersToE164(String raw, String expected) {
        Identifier id = Identifier.parse(raw, "91");

        assertThat(id.type()).isEqualTo(IdentifierType.PHONE);
        assertThat(id.value()).isEqualTo(expected);
    }

    /**
     * Emails are trimmed and lower-cased so one person maps to one account.
     * <ul>
     *   <li><b>Given</b> {@code "  Rahul.Sharma@Example.COM "}</li>
     *   <li><b>When</b> it is parsed</li>
     *   <li><b>Then</b> the result is the EMAIL identifier {@code rahul.sharma@example.com}</li>
     * </ul>
     */
    @Test
    void lowerCasesAndTrimsEmail() {
        Identifier id = Identifier.parse("  Rahul.Sharma@Example.COM ", "91");

        assertThat(id).isEqualTo(new Identifier(IdentifierType.EMAIL, "rahul.sharma@example.com"));
    }

    /**
     * Invalid input is rejected with a stable error code.
     * <ul>
     *   <li><b>Given</b> null, blank, too short, a non-mobile 10-digit number, plain text, or an incomplete email</li>
     *   <li><b>When</b> it is parsed</li>
     *   <li><b>Then</b> an {@code ApiException} with code {@code INVALID_IDENTIFIER} is thrown</li>
     * </ul>
     *
     * @param raw invalid input
     */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "12345", "5876543210", "abc", "user@", "@example.com", "user@example", "+12"})
    void rejectsInvalidInput(String raw) {
        assertThatThrownBy(() -> Identifier.parse(raw, "91"))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("INVALID_IDENTIFIER");
    }

    /**
     * Identifiers are masked for display.
     * <ul>
     *   <li><b>Given</b> an email and a +91 phone number</li>
     *   <li><b>When</b> {@code masked()} is called</li>
     *   <li><b>Then</b> only the first letter of the email's local part and the last 4 phone digits remain visible</li>
     * </ul>
     */
    @Test
    void masksEmailAndPhoneForDisplay() {
        assertThat(Identifier.parse("rahul@example.com", "91").masked()).isEqualTo("r****@example.com");
        assertThat(Identifier.parse("9876543210", "91").masked()).isEqualTo("+91******3210");
    }

    /**
     * Logging an identifier never exposes it.
     * <ul>
     *   <li><b>Given</b> a parsed phone number</li>
     *   <li><b>When</b> {@code toString()} is called</li>
     *   <li><b>Then</b> the full number does not appear</li>
     * </ul>
     */
    @Test
    void toStringNeverLeaksTheFullValue() {
        assertThat(Identifier.parse("9876543210", "91").toString()).doesNotContain("9876543210");
    }
}
