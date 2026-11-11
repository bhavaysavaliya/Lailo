package com.lailo.application.features.cart.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.With;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One line of a shopper's cart (Postgres write model).
 *
 * <p>There is no {@code carts} table: a shopper has exactly one cart, so the user id <em>is</em> the cart's
 * identity. That removes a join from every read and a parent row from every write, and the unique index on
 * {@code (user_id, product_id)} is what keeps one line per product.</p>
 */
@Table("cart_items")
@Getter
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CartItem {

    /** Most units of one product a single cart line may hold, matching the database check constraint. */
    public static final int MAX_QUANTITY = 20;

    @Id
    private UUID id;
    private UUID userId;
    private UUID productId;
    private int quantity;
    /** Price when the item was added; shown as "was", never charged. The order re-reads the live price. */
    private BigDecimal unitPrice;
    private String currency;
    private Instant createdAt;
    private Instant updatedAt;
    /** Optimistic locking; null marks a new entity for Spring Data. */
    @Version
    private Long version;

    /**
     * Creates a new cart line.
     *
     * @param userId the shopper
     * @param productId product being added
     * @param quantity units to add
     * @param unitPrice price at the moment of adding
     * @param currency ISO 4217 code
     * @param now time of adding
     * @return an unsaved line with a {@code null} version, so Spring Data inserts it
     */
    public static CartItem create(UUID userId, UUID productId, int quantity, BigDecimal unitPrice,
                                  String currency, Instant now) {
        return CartItem.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .productId(productId)
                .quantity(quantity)
                .unitPrice(unitPrice)
                .currency(currency)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    /**
     * Returns a copy holding a different number of units, refreshing the remembered price so the "was" value
     * never describes a different quantity than the one on screen.
     *
     * @param newQuantity units the line should hold
     * @param unitPrice current price
     * @param now change time
     * @return the updated line (immutable style)
     */
    public CartItem withQuantity(int newQuantity, BigDecimal unitPrice, Instant now) {
        return this.withQuantity(newQuantity).withUnitPrice(unitPrice).withUpdatedAt(now);
    }
}
