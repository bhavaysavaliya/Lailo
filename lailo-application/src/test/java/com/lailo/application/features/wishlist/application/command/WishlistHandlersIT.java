package com.lailo.application.features.wishlist.application.command;

import com.lailo.application.common.cqrs.CommandBus;
import com.lailo.application.common.cqrs.QueryBus;
import com.lailo.application.common.web.ApiException;
import com.lailo.application.features.auth.domain.User;
import com.lailo.application.features.auth.infrastructure.persistence.UserRepository;
import com.lailo.application.features.product.application.command.ArchiveProductCommand;
import com.lailo.application.features.product.application.command.CreateProductCommand;
import com.lailo.application.features.product.application.query.ProductView;
import com.lailo.application.features.wishlist.application.query.GetWishlistQuery;
import com.lailo.application.features.wishlist.application.query.WishlistItemView;
import com.lailo.application.features.wishlist.application.query.WishlistView;
import com.lailo.application.features.wishlist.domain.RemoveWishlistItemReason;
import com.lailo.application.support.CommerceFixtures;
import com.lailo.application.support.IntegrationTest;
import com.lailo.application.support.ProductFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.r2dbc.core.DatabaseClient;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Wishlist write and read sides against real PostgreSQL and a real catalogue. */
class WishlistHandlersIT extends IntegrationTest {

    @Autowired CommandBus commandBus;
    @Autowired QueryBus queryBus;
    @Autowired UserRepository users;
    @Autowired DatabaseClient db;

    /**
     * Stores a throwaway shopper, which the wishlist's foreign key requires.
     *
     * @return the saved user
     */
    private User shopper() {
        return CommerceFixtures.signedInUser(users, uniqueEmail());
    }

    /**
     * Adds a product to the catalogue through its own command.
     *
     * @param name display name
     * @param stock units available
     * @return the created product
     */
    private ProductView product(String name, int stock) {
        return commandBus.dispatch(new CreateProductCommand(ProductFixtures
                .draft(name, ProductFixtures.uniqueCategory())
                .price(new BigDecimal("1299.00"))
                .stockQuantity(stock)
                .build())).block();
    }

    /**
     * Reads the wishlist event types recorded in the outbox for one shopper, oldest first.
     *
     * @param userId the shopper
     * @return the recorded event types
     */
    private List<String> wishlistEvents(UUID userId) {
        return db.sql("""
                        SELECT event_type FROM outbox_messages
                        WHERE aggregate_type = 'Wishlist' AND aggregate_id = :id ORDER BY created_at
                        """)
                .bind("id", userId.toString()).map(r -> r.get(0, String.class)).all().collectList().block();
    }

    /**
     * Saving a product twice keeps one entry and only updates the note.
     * <ul>
     *   <li><b>Given</b> a shopper and a product</li>
     *   <li><b>When</b> it is saved with a note, then saved again with a different note</li>
     *   <li><b>Then</b> there is one entry carrying the newer note, and only the first save produced an
     *       event - so a "back in stock" consumer is not told twice that someone is waiting</li>
     * </ul>
     */
    @Test
    void savingTheSameProductTwiceIsIdempotent() {
        User shopper = shopper();
        UUID productId = UUID.fromString(product("Kanjivaram Saree", 3).id());

        commandBus.dispatch(new AddToWishlistCommand(shopper.getId(), productId, "for Diwali")).block();
        WishlistView list = commandBus.dispatch(
                new AddToWishlistCommand(shopper.getId(), productId, "for the wedding")).block();

        assertThat(list.itemCount()).isEqualTo(1);
        assertThat(list.items().getFirst().note()).isEqualTo("for the wedding");
        assertThat(wishlistEvents(shopper.getId())).containsExactly("WishlistItemAdded");
    }

    /**
     * A wishlist reports live availability, which is what makes it worth keeping.
     * <ul>
     *   <li><b>Given</b> a saved product that is out of stock and another that has been archived</li>
     *   <li><b>When</b> the wishlist is read</li>
     *   <li><b>Then</b> the out-of-stock one is available but not in stock, and the archived one is
     *       neither - yet both are still listed, because the shopper chose to remember them</li>
     * </ul>
     */
    @Test
    void showsLiveStockAndAvailability() {
        User shopper = shopper();
        UUID soldOut = UUID.fromString(product("Sold Out Rug", 0).id());
        UUID archived = UUID.fromString(product("Retired Vase", 5).id());
        commandBus.dispatch(new AddToWishlistCommand(shopper.getId(), soldOut, null)).block();
        commandBus.dispatch(new AddToWishlistCommand(shopper.getId(), archived, null)).block();
        commandBus.dispatch(new ArchiveProductCommand(archived)).block();

        WishlistView list = queryBus.ask(new GetWishlistQuery(shopper.getId())).block();

        assertThat(list.itemCount()).isEqualTo(2);
        WishlistItemView soldOutItem = list.items().stream()
                .filter(i -> i.productId().equals(soldOut.toString())).findFirst().orElseThrow();
        WishlistItemView archivedItem = list.items().stream()
                .filter(i -> i.productId().equals(archived.toString())).findFirst().orElseThrow();
        assertThat(soldOutItem.available()).isTrue();
        assertThat(soldOutItem.inStock()).isFalse();
        assertThat(archivedItem.available()).isFalse();
        assertThat(archivedItem.inStock()).isFalse();
    }

    /**
     * Unsaving a product that is not saved is a 404 rather than a silent success.
     * <ul>
     *   <li><b>Given</b> an empty wishlist</li>
     *   <li><b>When</b> a product is removed from it</li>
     *   <li><b>Then</b> it fails with {@code WISHLIST_ITEM_NOT_FOUND}</li>
     * </ul>
     */
    @Test
    void refusesToRemoveSomethingThatIsNotSaved() {
        User shopper = shopper();
        UUID productId = UUID.fromString(product("Never Saved", 2).id());

        assertThatThrownBy(() -> commandBus.dispatch(new RemoveFromWishlistCommand(
                shopper.getId(), productId, RemoveWishlistItemReason.REMOVED_BY_USER)).block())
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("WISHLIST_ITEM_NOT_FOUND");
    }

    /**
     * Moving a saved product into the cart updates both lists in one step.
     * <ul>
     *   <li><b>Given</b> a saved product with stock</li>
     *   <li><b>When</b> it is moved to the cart with a quantity of two</li>
     *   <li><b>Then</b> the response carries a cart holding two units and an empty wishlist, and the removal
     *       is recorded as {@code MOVED_TO_CART} so analytics can tell it apart from an unsave</li>
     * </ul>
     */
    @Test
    void movesASavedProductIntoTheCart() {
        User shopper = shopper();
        UUID productId = UUID.fromString(product("Moving Item", 6).id());
        commandBus.dispatch(new AddToWishlistCommand(shopper.getId(), productId, null)).block();

        MoveWishlistItemToCartCommand.Result result = commandBus.dispatch(
                new MoveWishlistItemToCartCommand(shopper.getId(), productId, 2)).block();

        assertThat(result.cart().totalQuantity()).isEqualTo(2);
        assertThat(result.wishlist().itemCount()).isZero();
        assertThat(wishlistEvents(shopper.getId()))
                .containsExactly("WishlistItemAdded", "WishlistItemRemoved");
        String reason = db.sql("""
                        SELECT payload FROM outbox_messages
                        WHERE aggregate_type = 'Wishlist' AND event_type = 'WishlistItemRemoved'
                          AND aggregate_id = :id
                        """)
                .bind("id", shopper.getId().toString()).map(r -> r.get(0, String.class)).one().block();
        assertThat(reason).contains("MOVED_TO_CART");
    }

    /**
     * A move that the cart refuses leaves the wishlist untouched.
     * <ul>
     *   <li><b>Given</b> a saved product that has gone out of stock</li>
     *   <li><b>When</b> it is moved to the cart</li>
     *   <li><b>Then</b> it fails with {@code PRODUCT_OUT_OF_STOCK} and the product is still saved, because
     *       the add is attempted before the unsave and both share one transaction</li>
     * </ul>
     */
    @Test
    void leavesTheWishlistAloneWhenTheCartRefusesTheMove() {
        User shopper = shopper();
        UUID productId = UUID.fromString(product("Out Of Stock Favourite", 0).id());
        commandBus.dispatch(new AddToWishlistCommand(shopper.getId(), productId, null)).block();

        assertThatThrownBy(() -> commandBus.dispatch(
                new MoveWishlistItemToCartCommand(shopper.getId(), productId, 1)).block())
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("PRODUCT_OUT_OF_STOCK");

        assertThat(queryBus.ask(new GetWishlistQuery(shopper.getId())).block().itemCount()).isEqualTo(1);
    }
}
