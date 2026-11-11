package com.lailo.application.features.order.api.dto;

import com.lailo.application.features.order.domain.ShippingAddress;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Delivery address as entered at checkout. Bounds match the order columns, so a rejected value never
 * reaches the database.
 *
 * @param fullName name the courier asks for
 * @param phone contact number for the courier
 * @param line1 house or flat and street
 * @param line2 area or landmark, optional
 * @param city city or town
 * @param state state or union territory
 * @param postalCode PIN code
 * @param country ISO 3166-1 alpha-2 code; defaults to {@code IN}
 */
public record ShippingAddressRequest(
        @NotBlank(message = "Enter the recipient's name.") @Size(max = 120) String fullName,
        @NotBlank(message = "Enter a contact number.") @Size(max = 16) String phone,
        @NotBlank(message = "Enter the house or flat and street.") @Size(max = 200) String line1,
        @Size(max = 200) String line2,
        @NotBlank(message = "Enter the city.") @Size(max = 120) String city,
        @NotBlank(message = "Enter the state.") @Size(max = 120) String state,
        @NotBlank(message = "Enter the PIN code.")
        @Pattern(regexp = "[1-9]\\d{5}", message = "Enter a valid 6-digit PIN code.") String postalCode,
        @Size(min = 2, max = 2) String country) {

    /**
     * Converts the validated body into the normalised value object the order stores.
     *
     * @return the address
     */
    public ShippingAddress toAddress() {
        return ShippingAddress.builder()
                .fullName(fullName)
                .phone(phone)
                .line1(line1)
                .line2(line2)
                .city(city)
                .state(state)
                .postalCode(postalCode)
                .country(country)
                .build();
    }
}
