package com.lailo.application.support;

import com.lailo.application.features.auth.domain.Identifier;
import com.lailo.application.features.auth.domain.User;
import com.lailo.application.features.auth.infrastructure.persistence.UserRepository;
import com.lailo.application.features.order.domain.Order;
import com.lailo.application.features.order.domain.OrderItem;
import com.lailo.application.features.order.domain.OrderNumber;
import com.lailo.application.features.order.domain.OrderTotals;
import com.lailo.application.features.order.domain.ShippingAddress;
import com.lailo.application.features.order.domain.event.OrderEvents;
import com.lailo.application.features.order.infrastructure.persistence.OrderItemRepository;
import com.lailo.application.features.order.infrastructure.persistence.OrderRepository;
import com.lailo.application.features.payment.domain.PaymentMethod;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Builders for cart, wishlist, order and payment test data.
 *
 * <p>Carts and wishlists have a foreign key to {@code users}, so a test cannot invent a user id the way the
 * catalogue tests invent a token subject: {@link #signedInUser} stores a real account.</p>
 */
public final class CommerceFixtures {

    /** Not instantiable: static factories only. */
    private CommerceFixtures() {
    }

    /**
     * Stores a throwaway account with a unique email, as sign-in would.
     *
     * @param users the user repository
     * @param email the account's email; use {@code uniqueEmail()} from the test base class
     * @return the saved user
     */
    public static User signedInUser(UserRepository users, String email) {
        return users.save(User.register(UUID.randomUUID(), Identifier.parse(email, "91"), Instant.now()))
                .block();
    }

    /**
     * Stores a throwaway account reachable on both channels, which is what an order notification needs in
     * order to send an email <em>and</em> a text message.
     *
     * @param users the user repository
     * @param email the account's email
     * @param tenDigitPhone a 10-digit Indian mobile number; use {@code uniquePhone()} from the test base
     * @return the saved user
     */
    public static User reachableUser(UserRepository users, String email, String tenDigitPhone) {
        User user = User.register(UUID.randomUUID(), Identifier.parse(email, "91"), Instant.now())
                .withPhone(Identifier.parse(tenDigitPhone, "91").value())
                .withPhoneVerified(true);
        return users.save(user).block();
    }

    /**
     * Stores an order already past payment, bypassing checkout and therefore the saga.
     *
     * <p>A test about fulfilment transitions wants an order in a known state, not a race with the
     * consumers: placing a real order would have the saga reserving stock and opening a payment in the
     * background, so the status under test would depend on timing. The write model is the source of truth,
     * so writing it directly is the honest fixture here.</p>
     *
     * @param orders the order repository
     * @param items the order-line repository
     * @param userId the shopper the order belongs to
     * @param productId the product on its single line
     * @return the saved, confirmed order
     */
    public static Order confirmedOrder(OrderRepository orders, OrderItemRepository items, UUID userId,
                                       UUID productId) {
        Instant now = Instant.now();
        UUID orderId = UUID.randomUUID();
        OrderTotals totals = OrderTotals.of(new BigDecimal("999.00"), BigDecimal.ZERO, "INR");
        Order order = Order.place(orderId, OrderNumber.generate(now, new SecureRandom()), userId, totals,
                        PaymentMethod.COD, address().build(), now)
                .confirm(now);
        // The order row first: order_items has a foreign key to it.
        Order saved = orders.save(order).block();
        items.save(OrderItem.snapshot(orderId, productId, "Fixture Product", "fixture-product", null, 1,
                new BigDecimal("999.00"), "INR")).block();
        return saved;
    }

    /**
     * Stores an order waiting for payment, with one line, and without publishing {@code OrderPlaced}.
     *
     * <p>The missing event is the point: the checkout saga is a live consumer in the shared test context, so
     * an order placed through the real command would be picked up by the real saga and race any assertion.
     * Writing the order directly lets a test hand the saga exactly the event it wants to study.</p>
     *
     * @param orders the order repository
     * @param items the order-line repository
     * @param userId the shopper
     * @param productId the product on the line
     * @param quantity units on the line
     * @param unitPrice price charged per unit
     * @return the saved order
     */
    public static Order pendingOrder(OrderRepository orders, OrderItemRepository items, UUID userId,
                                     UUID productId, int quantity, BigDecimal unitPrice) {
        Instant now = Instant.now();
        UUID orderId = UUID.randomUUID();
        BigDecimal itemsTotal = unitPrice.multiply(BigDecimal.valueOf(quantity));
        Order order = Order.place(orderId, OrderNumber.generate(now, new SecureRandom()), userId,
                OrderTotals.of(itemsTotal, BigDecimal.ZERO, "INR"), PaymentMethod.CARD,
                address().build(), now);
        Order saved = orders.save(order).block();
        items.save(OrderItem.snapshot(orderId, productId, "Saga Product", "saga-product", null, quantity,
                unitPrice, "INR")).block();
        return saved;
    }

    /**
     * Builds the {@code OrderPlaced} event the given order would have published.
     *
     * @param order the stored order
     * @param productId the product on its line
     * @param quantity units on the line
     * @param unitPrice price charged per unit
     * @return the event, with a fresh event id
     */
    public static OrderEvents.OrderPlaced placedEvent(Order order, UUID productId, int quantity,
                                                      BigDecimal unitPrice) {
        return OrderEvents.OrderPlaced.builder()
                .eventId(UUID.randomUUID().toString())
                .orderId(order.getId().toString())
                .orderNumber(order.getOrderNumber())
                .userId(order.getUserId().toString())
                .status(order.getStatus().name())
                .items(List.of(OrderEvents.Line.builder()
                        .productId(productId.toString())
                        .productName("Saga Product")
                        .productSlug("saga-product")
                        .quantity(quantity)
                        .unitPrice(unitPrice)
                        .lineTotal(unitPrice.multiply(BigDecimal.valueOf(quantity)))
                        .build()))
                .itemsTotal(order.getItemsTotal())
                .shippingFee(order.getShippingFee())
                .totalAmount(order.getTotalAmount())
                .currency(order.getCurrency())
                .paymentMethod(order.getPaymentMethod().name())
                .shippingAddressLine(order.getShippingAddress().singleLine())
                .shippingRecipient(order.getShippingAddress().fullName())
                .occurredAt(order.getPlacedAt())
                .build();
    }

    /**
     * Starts a valid Indian delivery address. A test overrides only the fields it cares about.
     *
     * @return a prefilled builder
     */
    public static ShippingAddress.ShippingAddressBuilder address() {
        return ShippingAddress.builder()
                .fullName("Aarav Mehta")
                .phone("+919876543210")
                .line1("12 Chandni Chowk")
                .line2("Near Red Fort")
                .city("New Delhi")
                .state("Delhi")
                .postalCode("110006")
                .country("IN");
    }
}
