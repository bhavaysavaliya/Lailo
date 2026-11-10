package com.lailo.application.features.product.application.query;

import com.lailo.application.common.cqrs.Query;

import java.util.UUID;

/**
 * Query for a single product, including archived ones so order history stays readable.
 *
 * @param productId the product to read
 */
public record GetProductQuery(UUID productId) implements Query<ProductView> {
}
