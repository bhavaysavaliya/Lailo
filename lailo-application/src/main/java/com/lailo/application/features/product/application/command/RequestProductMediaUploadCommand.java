package com.lailo.application.features.product.application.command;

import com.lailo.application.common.cqrs.Command;
import lombok.Builder;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Ask for permission to upload one image or video. The bytes go straight from the client to the object
 * store, so this service only reserves the slot and signs the URL.
 *
 * @param productId product the media belongs to
 * @param contentType content type the client will send
 * @param sizeBytes exact number of bytes the client will send
 */
@Builder
public record RequestProductMediaUploadCommand(UUID productId, String contentType, long sizeBytes)
        implements Command<RequestProductMediaUploadCommand.Result> {

    /**
     * Everything the client needs to perform the upload and then confirm it.
     *
     * @param mediaId id to confirm the upload with
     * @param type {@code IMAGE} or {@code VIDEO}
     * @param uploadUrl presigned URL, valid until {@code expiresAt}
     * @param httpMethod method to use, {@code PUT}
     * @param headers headers that must be sent verbatim; they are part of the signature
     * @param expiresAt when the URL stops working
     * @param confirmUrl endpoint to call once the bytes are uploaded
     */
    @Builder
    public record Result(String mediaId, String type, String uploadUrl, String httpMethod,
                         Map<String, String> headers, Instant expiresAt, String confirmUrl) {
    }
}
