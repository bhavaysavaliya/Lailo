package com.lailo.application.features.order.infrastructure.readmodel;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

import java.math.BigDecimal;

/**
 * One line inside an order document. Held in an array rather than a map, which is safe here precisely
 * because order lines are immutable: they are written once by {@code OrderPlaced} with {@code $set}, so a
 * redelivered event rewrites the same array instead of appending to it.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderItemDocument {
    private String productId;
    private String name;
    private String slug;
    private String imageUrl;
    private Integer quantity;
    /** Stored as Decimal128, not a string, so totals stay numeric for any later aggregation. */
    @Field(targetType = FieldType.DECIMAL128)
    private BigDecimal unitPrice;
    @Field(targetType = FieldType.DECIMAL128)
    private BigDecimal lineTotal;
    private String currency;
}
