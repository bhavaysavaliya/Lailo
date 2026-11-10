package com.lailo.application.features.product.application.command;

import com.lailo.application.common.cqrs.Command;

import java.util.UUID;

/**
 * Remove one image or video from a product.
 *
 * @param productId owning product
 * @param mediaId the media item to remove
 */
public record DeleteProductMediaCommand(UUID productId, UUID mediaId) implements Command<Boolean> {
}
