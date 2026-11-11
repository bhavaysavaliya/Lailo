package com.lailo.application.features.wishlist.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.With;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One saved product (Postgres write model). Like the cart, the wishlist is keyed by the user rather than by a
 * parent row: a shopper has one wishlist, and the unique index on {@code (user_id, product_id)} is what makes
 * saving the same product twice a no-op instead of a duplicate.
 */
@Table("wishlist_items")
@Getter
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WishlistItem {

    /** Longest note a shopper may attach, matching the database column. */
    public static final int MAX_NOTE_LENGTH = 280;

    @Id
    private UUID id;
    private UUID userId;
    private UUID productId;
    /** Why the shopper saved it ("for Diwali"), or {@code null}. */
    private String note;
    private Instant createdAt;
    /** Optimistic locking; null marks a new entity for Spring Data. */
    @Version
    private Long version;

    /**
     * Creates a saved-product entry.
     *
     * @param userId the shopper
     * @param productId product being saved
     * @param note optional note, trimmed by the caller
     * @param now time of saving
     * @return an unsaved entry with a {@code null} version, so Spring Data inserts it
     */
    public static WishlistItem create(UUID userId, UUID productId, String note, Instant now) {
        return WishlistItem.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .productId(productId)
                .note(note)
                .createdAt(now)
                .build();
    }
}
