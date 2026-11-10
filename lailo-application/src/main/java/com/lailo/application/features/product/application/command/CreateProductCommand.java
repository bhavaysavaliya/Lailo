package com.lailo.application.features.product.application.command;

import com.lailo.application.common.cqrs.Command;
import com.lailo.application.features.product.application.query.ProductView;
import com.lailo.application.features.product.domain.ProductDetails;

/**
 * Add a product to the catalogue.
 *
 * @param details normalised product details
 */
public record CreateProductCommand(ProductDetails details) implements Command<ProductView> {
}
