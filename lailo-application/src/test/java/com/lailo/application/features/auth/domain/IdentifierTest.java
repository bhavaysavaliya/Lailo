package com.lailo.application.features.auth.domain;

import com.lailo.application.common.web.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdentifierTest {

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

    @Test
    void lowerCasesAndTrimsEmail() {
        Identifier id = Identifier.parse("  Rahul.Sharma@Example.COM ", "91");

        assertThat(id).isEqualTo(new Identifier(IdentifierType.EMAIL, "rahul.sharma@example.com"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "12345", "5876543210", "abc", "user@", "@example.com", "user@example", "+12"})
    void rejectsInvalidInput(String raw) {
        assertThatThrownBy(() -> Identifier.parse(raw, "91"))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("INVALID_IDENTIFIER");
    }

    @Test
    void masksEmailAndPhoneForDisplay() {
        assertThat(Identifier.parse("rahul@example.com", "91").masked()).isEqualTo("r****@example.com");
        assertThat(Identifier.parse("9876543210", "91").masked()).isEqualTo("+91******3210");
    }

    @Test
    void toStringNeverLeaksTheFullValue() {
        assertThat(Identifier.parse("9876543210", "91").toString()).doesNotContain("9876543210");
    }
}
