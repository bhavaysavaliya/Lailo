package com.lailo.application.features.cart.application.command;

import com.lailo.application.common.cqrs.CommandBus;
import com.lailo.application.common.cqrs.QueryBus;
import com.lailo.application.common.web.ApiException;
import com.lailo.application.features.auth.domain.User;
import com.lailo.application.features.auth.infrastructure.persistence.UserRepository;
import com.lailo.application.features.cart.application.query.CartItemView;
import com.lailo.application.features.cart.application.query.CartView;
import com.lailo.application.features.cart.application.query.GetCartQuery;
import com.lailo.application.features.product.application.command.ArchiveProductCommand;
import com.lailo.application.features.product.application.command.CreateProductCommand;
import com.lailo.application.features.product.application.query.ProductView;
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

/** Cart write side against real PostgreSQL and a real catalogue: no repository or bus is mocked. */
class AddToCartHandlerIT extends IntegrationTest {

    @Autowired CommandBus commandBus;
    @Autowired QueryBus queryBus;
    @Autowired UserRepository users;
    @Autowired DatabaseClient db;

    /**
     * Stores a throwaway shopper, which the cart's foreign key requires.
     *
     * @return the saved user
     */
    private User shopper() {
        return CommerceFixtures.signedInUser(users, uniqueEmail());
    }

    /**
     * Adds a product to the catalogue through its own command, so the cart sees a real catalogue entry.
     *
     * @param name display name
     * @param price price in INR
     * @param stock units available
     * @return the created product
     */
    private ProductView product(String name, String price, int stock) {
        return commandBus.dispatch(new CreateProductCommand(ProductFixtures
                .draft(name, ProductFixtures.uniqueCategory())
                .price(new BigDecimal(price))
                .stockQuantity(stock)
                .build())).block();
    }

    /**
     * Reads the cart event types recorded in the outbox for one shopper, oldest first.
     *
     * @param userId the shopper
     * @return the recorded event types
     */
    private List<String> cartEvents(UUID userId) {
        return db.sql("""
                        SELECT event_type FROM outbox_messages
                        WHERE aggregate_type = 'Cart' AND aggregate_id = :id ORDER BY created_at
                        """)
                .bind("id", userId.toString()).map(r -> r.get(0, String.class)).all().collectList().block();
    }

    /**
     * A first add creates the line and records one event.
     * <ul>
     *   <li><b>Given</b> a shopper and a product priced 999.00 with 10 in stock</li>
     *   <li><b>When</b> two units are added</li>
     *   <li><b>Then</b> the returned cart holds one line of two units at the live price with the line total
     *       computed from it, and exactly one {@code CartItemAdded} event sits in the outbox</li>
     * </ul>
     */
    @Test
    void addsALineAndRecordsOneEvent() {
        User shopper = shopper();
        ProductView product = product("Jute Tote", "999.00", 10);

        CartView cart = commandBus.dispatch(
                new AddToCartCommand(shopper.getId(), UUID.fromString(product.id()), 2)).block();

        assertThat(cart.itemCount()).isEqualTo(1);
        assertThat(cart.totalQuantity()).isEqualTo(2);
        assertThat(cart.itemsTotal()).isEqualByComparingTo("1998.00");
        CartItemView line = cart.items().getFirst();
        assertThat(line.productId()).isEqualTo(product.id());
        assertThat(line.name()).isEqualTo("Jute Tote");
        assertThat(line.unitPrice()).isEqualByComparingTo("999.00");
        assertThat(line.lineTotal()).isEqualByComparingTo("1998.00");
        assertThat(line.available()).isTrue();
        assertThat(cartEvents(shopper.getId())).containsExactly("CartItemAdded");
    }

    /**
     * Adding the same product again raises the line rather than duplicating it.
     * <ul>
     *   <li><b>Given</b> a cart that already holds two units of a product</li>
     *   <li><b>When</b> three more are added</li>
     *   <li><b>Then</b> there is still one line, now of five units, and the second add is recorded as a
     *       quantity change</li>
     * </ul>
     */
    @Test
    void mergesARepeatedAddIntoTheSameLine() {
        User shopper = shopper();
        UUID productId = UUID.fromString(product("Clay Mug", "350.00", 20).id());

        commandBus.dispatch(new AddToCartCommand(shopper.getId(), productId, 2)).block();
        CartView cart = commandBus.dispatch(new AddToCartCommand(shopper.getId(), productId, 3)).block();

        assertThat(cart.itemCount()).isEqualTo(1);
        assertThat(cart.items().getFirst().quantity()).isEqualTo(5);
        assertThat(cartEvents(shopper.getId()))
                .containsExactly("CartItemAdded", "CartItemQuantityChanged");
    }

    /**
     * The cart never accepts more units than the catalogue has.
     * <ul>
     *   <li><b>Given</b> a product with two units in stock</li>
     *   <li><b>When</b> three units are added</li>
     *   <li><b>Then</b> it fails with {@code PRODUCT_OUT_OF_STOCK} reporting the two available, and the
     *       cart stays empty</li>
     * </ul>
     */
    @Test
    void refusesMoreUnitsThanAreInStock() {
        User shopper = shopper();
        UUID productId = UUID.fromString(product("Brass Diya", "199.00", 2).id());

        assertThatThrownBy(() -> commandBus
                .dispatch(new AddToCartCommand(shopper.getId(), productId, 3)).block())
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("PRODUCT_OUT_OF_STOCK");

        CartView cart = queryBus.ask(new GetCartQuery(shopper.getId())).block();
        assertThat(cart.isEmpty()).isTrue();
    }

    /**
     * A withdrawn product cannot be added.
     * <ul>
     *   <li><b>Given</b> a product that has been archived</li>
     *   <li><b>When</b> it is added to a cart</li>
     *   <li><b>Then</b> it fails with {@code PRODUCT_UNAVAILABLE}</li>
     * </ul>
     */
    @Test
    void refusesAnArchivedProduct() {
        User shopper = shopper();
        UUID productId = UUID.fromString(product("Discontinued Lamp", "1499.00", 5).id());
        commandBus.dispatch(new ArchiveProductCommand(productId)).block();

        assertThatThrownBy(() -> commandBus
                .dispatch(new AddToCartCommand(shopper.getId(), productId, 1)).block())
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("PRODUCT_UNAVAILABLE");
    }

    /**
     * One line may not hold wholesale quantities.
     * <ul>
     *   <li><b>Given</b> a product with plenty of stock</li>
     *   <li><b>When</b> 21 units are added, one more than the per-line cap</li>
     *   <li><b>Then</b> it fails with {@code CART_QUANTITY_LIMIT}</li>
     * </ul>
     */
    @Test
    void refusesMoreThanThePerLineCap() {
        User shopper = shopper();
        UUID productId = UUID.fromString(product("Cotton Napkin", "99.00", 500).id());

        assertThatThrownBy(() -> commandBus
                .dispatch(new AddToCartCommand(shopper.getId(), productId, 21)).block())
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("CART_QUANTITY_LIMIT");
    }

    /**
     * A line is priced from the catalogue every time it is read, not from what it cost when it was added.
     * <ul>
     *   <li><b>Given</b> a cart holding a product at 999.00</li>
     *   <li><b>When</b> the product is repriced to 799.00 and the cart is read again</li>
     *   <li><b>Then</b> the line shows the new price and the old one side by side, flags the change, and
     *       the cart total uses the new price</li>
     * </ul>
     */
    @Test
    void pricesLinesLiveAndShowsWhatTheyCostWhenAdded() {
        User shopper = shopper();
        ProductView product = product("Linen Shirt", "999.00", 10);
        UUID productId = UUID.fromString(product.id());
        commandBus.dispatch(new AddToCartCommand(shopper.getId(), productId, 2)).block();

        commandBus.dispatch(new com.lailo.application.features.product.application.command
                .UpdateProductCommand(productId, ProductFixtures.draft("Linen Shirt", product.category())
                .price(new BigDecimal("799.00")).stockQuantity(10).build())).block();

        CartView cart = queryBus.ask(new GetCartQuery(shopper.getId())).block();
        CartItemView line = cart.items().getFirst();
        assertThat(line.unitPrice()).isEqualByComparingTo("799.00");
        assertThat(line.unitPriceWhenAdded()).isEqualByComparingTo("999.00");
        assertThat(line.priceChanged()).isTrue();
        assertThat(cart.itemsTotal()).isEqualByComparingTo("1598.00");
    }

    /**
     * A product that leaves the catalogue is shown as unavailable instead of disappearing from the cart.
     * <ul>
     *   <li><b>Given</b> a cart holding a product</li>
     *   <li><b>When</b> the product is archived and the cart is read</li>
     *   <li><b>Then</b> the line is still there, marked unavailable, the cart reports that it has
     *       unavailable items, and the total excludes it</li>
     * </ul>
     */
    @Test
    void keepsAWithdrawnProductVisibleButUnavailable() {
        User shopper = shopper();
        UUID productId = UUID.fromString(product("Seasonal Candle", "249.00", 4).id());
        commandBus.dispatch(new AddToCartCommand(shopper.getId(), productId, 1)).block();

        commandBus.dispatch(new ArchiveProductCommand(productId)).block();

        CartView cart = queryBus.ask(new GetCartQuery(shopper.getId())).block();
        assertThat(cart.itemCount()).isEqualTo(1);
        assertThat(cart.items().getFirst().available()).isFalse();
        assertThat(cart.hasUnavailableItems()).isTrue();
        assertThat(cart.itemsTotal()).isEqualByComparingTo("0.00");
    }

    /**
     * Removing a line that is not there is a 404 rather than a silent success.
     * <ul>
     *   <li><b>Given</b> an empty cart</li>
     *   <li><b>When</b> a product is removed from it</li>
     *   <li><b>Then</b> it fails with {@code CART_ITEM_NOT_FOUND}</li>
     * </ul>
     */
    @Test
    void refusesToRemoveSomethingThatIsNotInTheCart() {
        User shopper = shopper();
        UUID productId = UUID.fromString(product("Absent Item", "149.00", 3).id());

        assertThatThrownBy(() -> commandBus
                .dispatch(new RemoveFromCartCommand(shopper.getId(), productId)).block())
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("CART_ITEM_NOT_FOUND");
    }

    /**
     * Editing and emptying a cart work end to end.
     * <ul>
     *   <li><b>Given</b> a cart holding two units of a product</li>
     *   <li><b>When</b> the line is set to one unit, then the cart is cleared twice</li>
     *   <li><b>Then</b> the line holds one unit, the first clear reports that it emptied something, the
     *       second reports nothing to do, and the events tell the whole story</li>
     * </ul>
     */
    @Test
    void updatesAndClearsTheCart() {
        User shopper = shopper();
        UUID productId = UUID.fromString(product("Steel Tiffin", "749.00", 9).id());
        commandBus.dispatch(new AddToCartCommand(shopper.getId(), productId, 2)).block();

        CartView updated = commandBus.dispatch(
                new UpdateCartItemCommand(shopper.getId(), productId, 1)).block();
        assertThat(updated.items().getFirst().quantity()).isEqualTo(1);

        Boolean first = commandBus.dispatch(new ClearCartCommand(shopper.getId(),
                com.lailo.application.features.cart.domain.ClearCartReason.CLEARED_BY_USER)).block();
        Boolean second = commandBus.dispatch(new ClearCartCommand(shopper.getId(),
                com.lailo.application.features.cart.domain.ClearCartReason.CLEARED_BY_USER)).block();

        assertThat(first).isTrue();
        assertThat(second).isFalse();
        assertThat(queryBus.ask(new GetCartQuery(shopper.getId())).block().isEmpty()).isTrue();
        assertThat(cartEvents(shopper.getId()))
                .containsExactly("CartItemAdded", "CartItemQuantityChanged", "CartCleared");
    }
}
