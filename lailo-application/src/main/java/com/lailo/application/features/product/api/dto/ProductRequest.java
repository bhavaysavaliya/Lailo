package com.lailo.application.features.product.api.dto;

import com.lailo.application.features.product.domain.ProductDetails;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Request body for creating and updating a product. Bounds match the write-model columns, so a rejected
 * value never reaches the database.
 *
 * @param name display name
 * @param description long description, optional
 * @param brand brand name, optional
 * @param category catalogue category
 * @param price price in INR, at most two decimals
 * @param stockQuantity units available
 */
public record ProductRequest(
        @NotBlank(message = "Enter a product name.") @Size(max = 200) String name,
        @Size(max = 4000) String description,
        @Size(max = 120) String brand,
        @NotBlank(message = "Choose a category.") @Size(max = 120) String category,
        @NotNull(message = "Enter a price.")
        @DecimalMin(value = "0.01", message = "Price must be greater than zero.")
        @DecimalMax(value = "9999999999.99", message = "Price is too high.")
        @Digits(integer = 10, fraction = 2, message = "Price may have at most two decimals.") BigDecimal price,
        @NotNull(message = "Enter the available quantity.") @Min(0) @Max(1_000_000) Integer stockQuantity) {

    /**
     * Converts the validated body into normalised domain details.
     *
     * @return the details a command carries
     */
    public ProductDetails toDetails() {
        return ProductDetails.builder()
                .name(name)
                .description(description)
                .brand(brand)
                .category(category)
                .price(price)
                .stockQuantity(stockQuantity)
                .build();
    }
}
