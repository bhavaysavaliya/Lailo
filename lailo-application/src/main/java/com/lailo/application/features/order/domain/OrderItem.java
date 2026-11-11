package com.lailo.application.features.order.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One line of an order (Postgres write model), and a deliberate snapshot: name, image and price are copied in
 * at checkout and never refreshed, because an invoice has to keep saying what was bought at what price even
 * after the product is renamed, repriced or removed.
 */
@Table("order_items")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderItem {

    @Id
    private UUID id;
    private UUID orderId;
    private UUID productId;
    private String productName;
    private String productSlug;
    private String imageUrl;
    private int quantity;
    private BigDecimal unitPrice;
    private BigDecimal lineTotal;
    private String currency;
    /** Optimistic locking; null marks a new entity for Spring Data. */
    @Version
    private Long version;

    /**
     * Creates a snapshot line.
     *
     * @param orderId owning order
     * @param productId product bought
     * @param productName name at the time of the order
     * @param productSlug URL segment at the time of the order
     * @param imageUrl gallery image at the time of the order, or {@code null}
     * @param quantity units bought
     * @param unitPrice price charged per unit
     * @param currency ISO 4217 code
     * @return an unsaved line with a {@code null} version, so Spring Data inserts it
     */
    public static OrderItem snapshot(UUID orderId, UUID productId, String productName, String productSlug,
                                     String imageUrl, int quantity, BigDecimal unitPrice, String currency) {
        return OrderItem.builder()
                .id(UUID.randomUUID())
                .orderId(orderId)
                .productId(productId)
                .productName(productName)
                .productSlug(productSlug)
                .imageUrl(imageUrl)
                .quantity(quantity)
                .unitPrice(unitPrice)
                .lineTotal(unitPrice.multiply(BigDecimal.valueOf(quantity)))
                .currency(currency)
                .build();
    }
}
