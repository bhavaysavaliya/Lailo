package com.lailo.application.features.order.application.command;

import com.lailo.application.common.cqrs.CommandBus;
import com.lailo.application.common.cqrs.QueryBus;
import com.lailo.application.common.web.ApiException;
import com.lailo.application.features.auth.domain.User;
import com.lailo.application.features.auth.infrastructure.persistence.UserRepository;
import com.lailo.application.features.cart.application.command.AddToCartCommand;
import com.lailo.application.features.cart.application.query.GetCartQuery;
import com.lailo.application.features.order.application.query.OrderItemView;
import com.lailo.application.features.order.application.query.OrderView;
import com.lailo.application.features.order.domain.Order;
import com.lailo.application.features.order.domain.OrderBucket;
import com.lailo.application.features.order.domain.OrderStatus;
import com.lailo.application.features.order.infrastructure.persistence.OrderRepository;
import com.lailo.application.features.payment.domain.PaymentMethod;
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

/**
 * Checkout's one synchronous step, against real PostgreSQL: the order, its snapshotted lines, the emptied
 * cart and the {@code OrderPlaced} event all commit together, or none of them do.
 */
class PlaceOrderHandlerIT extends IntegrationTest {

    @Autowired CommandBus commandBus;
    @Autowired QueryBus queryBus;
    @Autowired UserRepository users;
    @Autowired OrderRepository orders;
    @Autowired DatabaseClient db;

    /**
     * Stores a throwaway shopper.
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
     * Puts a product in the shopper's cart.
     *
     * @param userId the shopper
     * @param productId the product
     * @param quantity units to add
     */
    private void addToCart(UUID userId, String productId, int quantity) {
        commandBus.dispatch(new AddToCartCommand(userId, UUID.fromString(productId), quantity)).block();
    }

    /**
     * Reads the order event types recorded in the outbox for one order, oldest first.
     *
     * @param orderId the order
     * @return the recorded event types
     */
    private List<String> orderEvents(String orderId) {
        return db.sql("""
                        SELECT event_type FROM outbox_messages
                        WHERE aggregate_type = 'Order' AND aggregate_id = :id ORDER BY created_at
                        """)
                .bind("id", orderId).map(r -> r.get(0, String.class)).all().collectList().block();
    }

    /**
     * A cart becomes an order, priced and snapshotted, and the cart is gone.
     * <ul>
     *   <li><b>Given</b> a cart holding two units at 150.00 and one at 100.00, which is under the 499.00
     *       free-delivery threshold</li>
     *   <li><b>When</b> the order is placed by card</li>
     *   <li><b>Then</b> it is {@code PENDING_PAYMENT} and in the "to be shipped" tab, the items total is
     *       400.00 with 49.00 delivery and 449.00 charged, both lines are snapshotted with their own line
     *       totals, the cart is empty, and exactly one {@code OrderPlaced} event is in the outbox</li>
     * </ul>
     */
    @Test
    void turnsTheCartIntoAPricedOrderAndEmptiesIt() {
        User shopper = shopper();
        ProductView first = product("Cotton Dhurrie", "150.00", 10);
        ProductView second = product("Jute Coaster", "100.00", 10);
        addToCart(shopper.getId(), first.id(), 2);
        addToCart(shopper.getId(), second.id(), 1);

        OrderView order = commandBus.dispatch(new PlaceOrderCommand(shopper.getId(),
                CommerceFixtures.address().build(), PaymentMethod.CARD)).block();

        assertThat(order.status()).isEqualTo(OrderStatus.PENDING_PAYMENT.name());
        assertThat(order.bucket()).isEqualTo(OrderBucket.TO_BE_SHIPPED.name());
        assertThat(order.orderNumber()).startsWith("LAILO-");
        assertThat(order.itemsTotal()).isEqualByComparingTo("400.00");
        assertThat(order.shippingFee()).isEqualByComparingTo("49.00");
        assertThat(order.totalAmount()).isEqualByComparingTo("449.00");
        assertThat(order.currency()).isEqualTo("INR");
        assertThat(order.totalQuantity()).isEqualTo(3);
        assertThat(order.paymentMethod()).isEqualTo("CARD");
        assertThat(order.shippingRecipient()).isEqualTo("Aarav Mehta");
        assertThat(order.shippingAddressLine()).contains("110006");

        OrderItemView dhurrie = order.items().stream()
                .filter(i -> i.productId().equals(first.id())).findFirst().orElseThrow();
        assertThat(dhurrie.name()).isEqualTo("Cotton Dhurrie");
        assertThat(dhurrie.quantity()).isEqualTo(2);
        assertThat(dhurrie.unitPrice()).isEqualByComparingTo("150.00");
        assertThat(dhurrie.lineTotal()).isEqualByComparingTo("300.00");

        assertThat(queryBus.ask(new GetCartQuery(shopper.getId())).block().isEmpty()).isTrue();
        assertThat(orderEvents(order.id())).containsExactly("OrderPlaced");
    }

    /**
     * The order keeps the address and the lines even after the product changes.
     * <ul>
     *   <li><b>Given</b> an order placed for a product</li>
     *   <li><b>When</b> the product is archived afterwards</li>
     *   <li><b>Then</b> the stored order line still carries the name and price it was bought at, because
     *       order lines are a snapshot and not a catalogue read</li>
     * </ul>
     */
    @Test
    void keepsLinesAsASnapshotOfWhatWasBought() {
        User shopper = shopper();
        ProductView bought = product("Limited Edition Print", "2500.00", 4);
        addToCart(shopper.getId(), bought.id(), 1);
        OrderView order = commandBus.dispatch(new PlaceOrderCommand(shopper.getId(),
                CommerceFixtures.address().build(), PaymentMethod.CARD)).block();

        commandBus.dispatch(new ArchiveProductCommand(UUID.fromString(bought.id()))).block();

        String storedName = db.sql("SELECT product_name FROM order_items WHERE order_id = :id")
                .bind("id", UUID.fromString(order.id())).map(r -> r.get(0, String.class)).one().block();
        assertThat(storedName).isEqualTo("Limited Edition Print");
        assertThat(order.items().getFirst().unitPrice()).isEqualByComparingTo("2500.00");
    }

    /**
     * Delivery is free above the configured threshold.
     * <ul>
     *   <li><b>Given</b> a cart worth 1299.00, above the 499.00 free-delivery threshold</li>
     *   <li><b>When</b> the order is placed</li>
     *   <li><b>Then</b> the delivery charge is zero and the total equals the items total</li>
     * </ul>
     */
    @Test
    void appliesFreeDeliveryAboveTheThreshold() {
        User shopper = shopper();
        addToCart(shopper.getId(), product("Wool Throw", "1299.00", 5).id(), 1);

        OrderView order = commandBus.dispatch(new PlaceOrderCommand(shopper.getId(),
                CommerceFixtures.address().build(), PaymentMethod.UPI)).block();

        assertThat(order.shippingFee()).isEqualByComparingTo("0.00");
        assertThat(order.totalAmount()).isEqualByComparingTo("1299.00");
    }

    /**
     * There is nothing to check out.
     * <ul>
     *   <li><b>Given</b> a shopper with an empty cart</li>
     *   <li><b>When</b> an order is placed</li>
     *   <li><b>Then</b> it fails with {@code CART_EMPTY} and no order row is written</li>
     * </ul>
     */
    @Test
    void refusesToCheckOutAnEmptyCart() {
        User shopper = shopper();

        assertThatThrownBy(() -> commandBus.dispatch(new PlaceOrderCommand(shopper.getId(),
                CommerceFixtures.address().build(), PaymentMethod.UPI)).block())
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("CART_EMPTY");

        assertThat(orders.findByUserIdOrderByPlacedAtDesc(shopper.getId()).collectList().block()).isEmpty();
    }

    /**
     * A cart with something unbuyable in it stops checkout rather than quietly dropping the line.
     * <ul>
     *   <li><b>Given</b> a cart holding a product that has since been archived</li>
     *   <li><b>When</b> an order is placed</li>
     *   <li><b>Then</b> it fails with {@code CART_HAS_UNAVAILABLE_ITEMS} and the cart is still intact, so
     *       the shopper can decide what to do</li>
     * </ul>
     */
    @Test
    void refusesToCheckOutACartWithUnavailableItems() {
        User shopper = shopper();
        ProductView gone = product("Vanishing Stool", "899.00", 3);
        addToCart(shopper.getId(), gone.id(), 1);
        commandBus.dispatch(new ArchiveProductCommand(UUID.fromString(gone.id()))).block();

        assertThatThrownBy(() -> commandBus.dispatch(new PlaceOrderCommand(shopper.getId(),
                CommerceFixtures.address().build(), PaymentMethod.CARD)).block())
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("CART_HAS_UNAVAILABLE_ITEMS");

        assertThat(queryBus.ask(new GetCartQuery(shopper.getId())).block().itemCount()).isEqualTo(1);
    }

    /**
     * The whole delivery address survives the round trip through the embedded columns.
     * <ul>
     *   <li><b>Given</b> an order placed to a full address</li>
     *   <li><b>When</b> the order is read back from the write model</li>
     *   <li><b>Then</b> every part of the address is intact, which is what the courier label depends on</li>
     * </ul>
     */
    @Test
    void storesTheWholeDeliveryAddress() {
        User shopper = shopper();
        addToCart(shopper.getId(), product("Address Check", "599.00", 2).id(), 1);
        OrderView placed = commandBus.dispatch(new PlaceOrderCommand(shopper.getId(),
                CommerceFixtures.address().build(), PaymentMethod.NETBANKING)).block();

        Order stored = orders.findById(UUID.fromString(placed.id())).block();

        assertThat(stored.getShippingAddress().fullName()).isEqualTo("Aarav Mehta");
        assertThat(stored.getShippingAddress().phone()).isEqualTo("+919876543210");
        assertThat(stored.getShippingAddress().line1()).isEqualTo("12 Chandni Chowk");
        assertThat(stored.getShippingAddress().line2()).isEqualTo("Near Red Fort");
        assertThat(stored.getShippingAddress().city()).isEqualTo("New Delhi");
        assertThat(stored.getShippingAddress().state()).isEqualTo("Delhi");
        assertThat(stored.getShippingAddress().postalCode()).isEqualTo("110006");
        assertThat(stored.getShippingAddress().country()).isEqualTo("IN");
    }
}
