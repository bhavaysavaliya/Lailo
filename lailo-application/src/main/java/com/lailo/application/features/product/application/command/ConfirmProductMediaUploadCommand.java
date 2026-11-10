package com.lailo.application.features.product.application.command;

import com.lailo.application.common.cqrs.Command;
import com.lailo.application.features.product.application.query.ProductMediaView;

import java.util.UUID;

/**
 * Confirm that the bytes of a reserved media slot are in the object store, which publishes it to the
 * catalogue.
 *
 * @param productId owning product
 * @param mediaId the reserved media item
 */
public record ConfirmProductMediaUploadCommand(UUID productId, UUID mediaId) implements Command<ProductMediaView> {
}
