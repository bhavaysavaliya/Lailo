package com.lailo.application.features.order.domain;

import com.lailo.application.support.CommerceFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests for delivery-address normalisation and validation (pure logic, no I/O). */
class ShippingAddressTest {

    /**
     * Whitespace a shopper types is cleaned up before it is stored.
     * <ul>
     *   <li><b>Given</b> an address whose fields are padded with spaces and an empty second line</li>
     *   <li><b>When</b> the address is built</li>
     *   <li><b>Then</b> every field is trimmed, the blank line becomes {@code null} and the country is
     *       upper-cased</li>
     * </ul>
     */
    @Test
    void trimsFieldsAndDropsBlankSecondLine() {
        ShippingAddress address = CommerceFixtures.address()
                .fullName("  Aarav Mehta  ")
                .line1(" 12 Chandni Chowk ")
                .line2("   ")
                .city(" New Delhi ")
                .country("in")
                .build();

        assertThat(address.fullName()).isEqualTo("Aarav Mehta");
        assertThat(address.line1()).isEqualTo("12 Chandni Chowk");
        assertThat(address.line2()).isNull();
        assertThat(address.city()).isEqualTo("New Delhi");
        assertThat(address.country()).isEqualTo("IN");
    }

    /**
     * An address with no country defaults to India, the only market served.
     * <ul>
     *   <li><b>Given</b> an address with no country</li>
     *   <li><b>When</b> it is built</li>
     *   <li><b>Then</b> the country is {@code IN}</li>
     * </ul>
     */
    @Test
    void defaultsCountryToIndia() {
        assertThat(CommerceFixtures.address().country(null).build().country()).isEqualTo("IN");
    }

    /**
     * A PIN code that no courier would accept is rejected at the boundary.
     * <ul>
     *   <li><b>Given</b> a PIN code that is too short, too long, non-numeric or starts with zero</li>
     *   <li><b>When</b> the address is built</li>
     *   <li><b>Then</b> it throws, so an undeliverable address never reaches the order</li>
     * </ul>
     *
     * @param postalCode the invalid PIN code
     */
    @ParameterizedTest
    @ValueSource(strings = {"11000", "1100066", "11000A", "011006"})
    void rejectsInvalidIndianPinCodes(String postalCode) {
        assertThatThrownBy(() -> CommerceFixtures.address().postalCode(postalCode).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("postalCode");
    }

    /**
     * A missing required field names itself.
     * <ul>
     *   <li><b>Given</b> an address with no recipient name</li>
     *   <li><b>When</b> it is built</li>
     *   <li><b>Then</b> it throws naming {@code fullName}</li>
     * </ul>
     */
    @Test
    void rejectsMissingRecipient() {
        assertThatThrownBy(() -> CommerceFixtures.address().fullName(" ").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fullName");
    }

    /**
     * The single-line rendering is what emails, texts and labels use.
     * <ul>
     *   <li><b>Given</b> a full address, and the same address without a second line</li>
     *   <li><b>When</b> each is rendered on one line</li>
     *   <li><b>Then</b> the parts appear in postal order and the missing line leaves no double comma</li>
     * </ul>
     */
    @Test
    void rendersOnOneLineForLabelsAndMessages() {
        assertThat(CommerceFixtures.address().build().singleLine())
                .isEqualTo("12 Chandni Chowk, Near Red Fort, New Delhi, Delhi 110006");
        assertThat(CommerceFixtures.address().line2(null).build().singleLine())
                .isEqualTo("12 Chandni Chowk, New Delhi, Delhi 110006");
    }
}
