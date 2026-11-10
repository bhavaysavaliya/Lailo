package com.lailo.application.features.product.api;

import com.lailo.application.common.cqrs.CommandBus;
import com.lailo.application.features.product.api.dto.MediaUploadRequest;
import com.lailo.application.features.product.application.command.ConfirmProductMediaUploadCommand;
import com.lailo.application.features.product.application.command.DeleteProductMediaCommand;
import com.lailo.application.features.product.application.command.RequestProductMediaUploadCommand;
import com.lailo.application.features.product.application.query.ProductMediaView;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Product images and videos. Uploads go straight from the client to the object store with a presigned URL:
 * this service signs, reserves and verifies, but never carries the bytes - which is what keeps a 300 MB
 * video upload from occupying an application thread or its memory.
 *
 * <pre>
 * 1. POST   /api/v1/products/{id}/media            -&gt; {uploadUrl, headers, mediaId, confirmUrl}
 * 2. PUT    {uploadUrl}                            -&gt; client sends the bytes to SeaweedFS directly
 * 3. POST   /api/v1/products/{id}/media/{mediaId}  -&gt; verified and published to the catalogue
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/products/{productId}/media")
@RequiredArgsConstructor
public class ProductMediaController {

    private final CommandBus commandBus;

    /**
     * Step 1: reserves a gallery slot and returns a presigned upload URL.
     *
     * @param productId product the media belongs to
     * @param req declared content type and size
     * @return the upload ticket; 415, 413 or 409 when the file is not acceptable
     */
    @PostMapping
    public Mono<ResponseEntity<RequestProductMediaUploadCommand.Result>> requestUpload(
            @PathVariable UUID productId, @Valid @RequestBody MediaUploadRequest req) {
        return commandBus.dispatch(RequestProductMediaUploadCommand.builder()
                        .productId(productId)
                        .contentType(req.contentType())
                        .sizeBytes(req.sizeBytes())
                        .build())
                .map(ticket -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ticket));
    }

    /**
     * Step 3: confirms the uploaded bytes and publishes the item. Idempotent.
     *
     * @param productId owning product
     * @param mediaId the reserved media item
     * @return the published gallery item
     */
    @PostMapping("/{mediaId}")
    public Mono<ProductMediaView> confirmUpload(@PathVariable UUID productId, @PathVariable UUID mediaId) {
        return commandBus.dispatch(new ConfirmProductMediaUploadCommand(productId, mediaId));
    }

    /**
     * Removes an image or video from the product. Idempotent.
     *
     * @param productId owning product
     * @param mediaId the media item to remove
     * @return 204 No Content, also when there was nothing to remove
     */
    @DeleteMapping("/{mediaId}")
    public Mono<ResponseEntity<Void>> delete(@PathVariable UUID productId, @PathVariable UUID mediaId) {
        return commandBus.dispatch(new DeleteProductMediaCommand(productId, mediaId))
                .thenReturn(ResponseEntity.noContent().build());
    }
}
