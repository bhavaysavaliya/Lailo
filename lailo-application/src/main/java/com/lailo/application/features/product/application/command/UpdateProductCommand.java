package com.lailo.application.features.product.application.command;

import com.lailo.application.common.cqrs.Command;
import com.lailo.application.features.product.application.query.ProductView;
import com.lailo.application.features.product.domain.ProductDetails;

import java.util.UUID;

/**
 * Replace a product's editable details. The slug is never changed, so existing links keep working.
 *
 * @param productId the product to change
 * @param details normalised product details
 */
public record UpdateProductCommand(UUID productId, ProductDetails details) implements Command<ProductView> {
}
