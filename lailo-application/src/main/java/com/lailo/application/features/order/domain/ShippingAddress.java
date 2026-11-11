package com.lailo.application.features.order.domain;

import lombok.Builder;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Where an order goes. Stored on the order as a snapshot: editing an address book entry later must not
 * rewrite where a past parcel was sent.
 *
 * @param fullName name the courier asks for
 * @param phone contact number in E.164, used by the courier
 * @param line1 house or flat and street
 * @param line2 area or landmark, or {@code null}
 * @param city city or town
 * @param state state or union territory
 * @param postalCode PIN code
 * @param country ISO 3166-1 alpha-2 code; only {@code IN} is served today
 */
@Builder
public record ShippingAddress(String fullName, String phone, String line1, String line2, String city,
                              String state, String postalCode, String country) {

    private static final Pattern IN_PIN = Pattern.compile("^[1-9]\\d{5}$");

    /** Normalises and then enforces what the courier integration needs, so a bad address fails here. */
    public ShippingAddress {
        fullName = trim(fullName);
        phone = trim(phone);
        line1 = trim(line1);
        line2 = trim(line2);
        city = trim(city);
        state = trim(state);
        postalCode = trim(postalCode);
        country = country == null || country.isBlank() ? "IN" : country.trim().toUpperCase(Locale.ROOT);
        require(fullName, "fullName");
        require(phone, "phone");
        require(line1, "line1");
        require(city, "city");
        require(state, "state");
        require(postalCode, "postalCode");
        if ("IN".equals(country) && !IN_PIN.matcher(postalCode).matches()) {
            throw new IllegalArgumentException("postalCode must be a 6-digit PIN code");
        }
    }

    /**
     * One-line rendering for emails, texts and labels.
     *
     * @return the address on a single line
     */
    public String singleLine() {
        StringBuilder sb = new StringBuilder(line1);
        if (line2 != null) {
            sb.append(", ").append(line2);
        }
        return sb.append(", ").append(city).append(", ").append(state).append(" ").append(postalCode).toString();
    }

    /**
     * Trims a value, mapping blanks to {@code null}.
     *
     * @param value raw value
     * @return the trimmed value, or {@code null}
     */
    private static String trim(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Fails when a required part of the address is missing.
     *
     * @param value the normalised value
     * @param field field name for the message
     */
    private static void require(String value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
