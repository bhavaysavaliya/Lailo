package com.lailo.application.features.product.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request body for reserving a media upload. The client declares what it is about to send; the signed URL
 * binds the store to exactly these values, so a mismatch fails at the store rather than later in the
 * catalogue.
 *
 * @param contentType content type of the file, e.g. {@code image/webp} or {@code video/mp4}
 * @param sizeBytes exact size of the file in bytes
 */
public record MediaUploadRequest(
        @NotBlank(message = "Tell us the file type.") @Size(max = 128) String contentType,
        @NotNull(message = "Tell us the file size.") @Min(value = 1, message = "The file is empty.")
        Long sizeBytes) {
}
