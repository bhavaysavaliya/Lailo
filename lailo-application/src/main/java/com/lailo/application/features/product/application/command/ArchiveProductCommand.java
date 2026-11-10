package com.lailo.application.features.product.application.command;

import com.lailo.application.common.cqrs.Command;

import java.util.UUID;

/**
 * Withdraw a product from the catalogue. The row is kept (orders still reference it), only its status changes.
 *
 * @param productId the product to withdraw
 */
public record ArchiveProductCommand(UUID productId) implements Command<Boolean> {
}
