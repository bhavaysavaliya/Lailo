package com.lailo.application.features.order;

import com.lailo.application.features.auth.domain.User;
import com.lailo.application.features.auth.infrastructure.persistence.UserRepository;
import com.lailo.application.features.auth.infrastructure.token.JwtTokenService;
import com.lailo.application.features.order.application.query.OrderView;
import com.lailo.application.features.order.domain.OrderBucket;
import com.lailo.application.features.order.domain.OrderStatus;
import com.lailo.application.features.payment.domain.PaymentStatus;
import com.lailo.application.features.payment.infrastructure.gateway.PaymentGateway;
import com.lailo.application.features.payment.infrastructure.gateway.SimulatedPaymentGateway;
import com.lailo.application.features.product.application.query.ProductView;
import com.lailo.application.support.CommerceFixtures;
import com.lailo.application.support.IntegrationTest;
import com.lailo.application.support.ProductFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * Checkout end to end over HTTP against real PostgreSQL, Kafka and MongoDB: cart to order, the saga
 * reserving stock and opening a payment, the notification going out, the orders list splitting into
 * "to be shipped" and "shipped", and compensation undoing everything when the gateway declines.
 *
 * <p>Nothing is stubbed except the two notification providers, which are spied so the test can prove a
 * message was handed to them.</p>
 */
class CheckoutFlowIT extends IntegrationTest {

    private static final Duration PROJECTION_TIMEOUT = Duration.ofSeconds(30);

    @Autowired WebTestClient client;
    @Autowired JwtTokenService jwt;
    @Autowired UserRepository users;
    @Autowired PaymentGateway gateway;
    @Autowired DatabaseClient db;

    private String xsrf;

    /**
     * Seeds the CSRF cookie the same way a browser does and remembers its value.
     *
     * @return the CSRF token
     */
    private String csrf() {
        if (xsrf == null) {
            xsrf = client.get().uri("/api/v1/auth/csrf").exchange().expectStatus().isNoContent()
                    .returnResult(Void.class).getResponseCookies().get("XSRF-TOKEN").getFirst().getValue();
        }
        return xsrf;
    }

    /**
     * Sends a state-changing request with a CSRF token, optionally authenticated.
     *
     * @param method HTTP method
     * @param uri request path
     * @param body JSON body, or {@code null} for none
     * @param token bearer token, or {@code null} to stay anonymous
     * @return the response spec for assertions
     */
    private WebTestClient.ResponseSpec send(HttpMethod method, String uri, Object body, String token) {
        WebTestClient.RequestBodySpec req = client.method(method).uri(uri)
                .header("X-XSRF-TOKEN", csrf())
                .cookie("XSRF-TOKEN", csrf());
        if (token != null) {
            req.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return body == null ? req.exchange() : req.contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange();
    }

    /**
     * Reads one of the caller's orders over HTTP.
     *
     * @param orderId the order
     * @param token the caller's bearer token
     * @return the order
     */
    private OrderView order(String orderId, String token) {
        return client.get().uri("/api/v1/orders/" + orderId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange().expectStatus().isOk()
                .expectBody(OrderView.class).returnResult().getResponseBody();
    }

    /**
     * Polls one of the caller's orders until it satisfies a condition, which is how an asynchronous saga
     * step is awaited.
     *
     * @param orderId the order
     * @param token the caller's bearer token
     * @param until condition the order must satisfy
     * @return the order once it matches
     * @throws AssertionError if the condition is not met within the projection timeout
     */
    private OrderView awaitOrder(String orderId, String token, Predicate<OrderView> until) {
        Instant deadline = Instant.now().plus(PROJECTION_TIMEOUT);
        OrderView last = null;
        while (Instant.now().isBefore(deadline)) {
            last = order(orderId, token);
            if (until.test(last)) {
                return last;
            }
            sleep();
        }
        throw new AssertionError("Order " + orderId + " never reached the expected state; last seen status="
                + (last == null ? "none" : last.status() + ", payment=" + last.paymentStatus()));
    }

    /**
     * Polls the catalogue's stock figure in the write model until it matches.
     *
     * @param productId the product
     * @param expected units expected to remain
     * @throws AssertionError if the stock never settles on the expected figure
     */
    private void awaitStock(String productId, int expected) {
        Instant deadline = Instant.now().plus(PROJECTION_TIMEOUT);
        Integer last = null;
        while (Instant.now().isBefore(deadline)) {
            last = db.sql("SELECT stock_quantity FROM products WHERE id = :id")
                    .bind("id", UUID.fromString(productId)).map(r -> r.get(0, Integer.class)).one().block();
            if (expected == last) {
                return;
            }
            sleep();
        }
        throw new AssertionError("Stock for " + productId + " was " + last + ", expected " + expected);
    }

    /** Waits briefly between polls without failing the test on interruption. */
    private static void sleep() {
        try {
            Thread.sleep(250);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    /**
     * Creates a product over HTTP.
     *
     * @param name display name
     * @param price price in INR
     * @param stock units available
     * @param token the caller's bearer token
     * @return the created product
     */
    private ProductView createProduct(String name, String price, int stock, String token) {
        return send(HttpMethod.POST, "/api/v1/products", Map.of("name", name,
                        "category", ProductFixtures.uniqueCategory(), "price", price, "stockQuantity", stock),
                token).expectStatus().isCreated()
                .expectBody(ProductView.class).returnResult().getResponseBody();
    }

    /**
     * Stores a shopper reachable by both email and phone and issues an access token for them.
     *
     * @return the shopper and their bearer token
     */
    private Shopper shopper() {
        User user = CommerceFixtures.reachableUser(users, uniqueEmail(), uniquePhone());
        return new Shopper(user, jwt.issue(user, UUID.randomUUID()).value());
    }

    /**
     * A signed-in shopper and their access token.
     *
     * @param user the stored account
     * @param token a valid bearer token for it
     */
    private record Shopper(User user, String token) {
    }

    /**
     * Cash on delivery goes all the way from an empty cart to a shipped order, and the shopper is told.
     * <ul>
     *   <li><b>Given</b> a signed-in shopper reachable by email and phone, and a product with five in
     *       stock</li>
     *   <li><b>When</b> two units are added to the cart and the order is placed for cash on delivery, then
     *       the order is shipped</li>
     *   <li><b>Then</b> the order starts {@code PENDING_PAYMENT}; the saga reserves the stock, opens an
     *       already-authorised COD payment and confirms the order; an email and a text message are handed to
     *       the providers; the cart is empty; the order sits in the "to be shipped" tab; and once shipped it
     *       moves to the "shipped" tab with its tracking details while "to be shipped" empties</li>
     * </ul>
     */
    @Test
    void placesConfirmsNotifiesAndShipsAnOrder() {
        Shopper shopper = shopper();
        ProductView product = createProduct("Handloom Bedsheet", "1299.00", 5, shopper.token());

        send(HttpMethod.POST, "/api/v1/cart/items",
                Map.of("productId", product.id(), "quantity", 2), shopper.token())
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.itemCount").isEqualTo(1)
                .jsonPath("$.itemsTotal").isEqualTo(2598.00);

        OrderView placed = send(HttpMethod.POST, "/api/v1/orders", Map.of(
                        "paymentMethod", "COD",
                        "address", Map.of("fullName", "Aarav Mehta", "phone", "+919876543210",
                                "line1", "12 Chandni Chowk", "city", "New Delhi", "state", "Delhi",
                                "postalCode", "110006")),
                shopper.token())
                .expectStatus().isCreated()
                .expectBody(OrderView.class).returnResult().getResponseBody();

        assertThat(placed.status()).isEqualTo(OrderStatus.PENDING_PAYMENT.name());
        assertThat(placed.bucket()).isEqualTo(OrderBucket.TO_BE_SHIPPED.name());
        assertThat(placed.totalAmount()).isEqualByComparingTo("2598.00");
        assertThat(placed.shippingFee()).isEqualByComparingTo("0.00");

        // Checkout emptied the cart in the same transaction that created the order.
        client.get().uri("/api/v1/cart").header(HttpHeaders.AUTHORIZATION, "Bearer " + shopper.token())
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.itemCount").isEqualTo(0);

        // The saga: reserve stock -> open a COD payment -> confirm the order.
        OrderView confirmed = awaitOrder(placed.id(), shopper.token(),
                o -> OrderStatus.CONFIRMED.name().equals(o.status()));
        assertThat(confirmed.paymentStatus()).isEqualTo(PaymentStatus.AUTHORIZED.name());
        assertThat(confirmed.paymentId()).isNotNull();
        awaitStock(product.id(), 3);

        // The notification consumer: an email and a text message for the order placed.
        verify(emailSender, timeout(PROJECTION_TIMEOUT.toMillis())).send(any());
        verify(messageSender, timeout(PROJECTION_TIMEOUT.toMillis())).send(any());

        // Waiting to be shipped, and not in the shipped tab.
        awaitList(shopper.token(), OrderBucket.TO_BE_SHIPPED, placed.id(), true);
        awaitList(shopper.token(), OrderBucket.SHIPPED, placed.id(), false);

        send(HttpMethod.POST, "/api/v1/orders/" + placed.id() + "/fulfilment",
                Map.of("target", "SHIPPED", "courierName", "Delhivery", "trackingNumber", "DL123456789IN",
                        "expectedDeliveryDate", "2026-11-15"),
                shopper.token()).expectStatus().isNoContent();

        OrderView shipped = awaitOrder(placed.id(), shopper.token(),
                o -> OrderStatus.SHIPPED.name().equals(o.status()));
        assertThat(shipped.bucket()).isEqualTo(OrderBucket.SHIPPED.name());
        assertThat(shipped.courierName()).isEqualTo("Delhivery");
        assertThat(shipped.trackingNumber()).isEqualTo("DL123456789IN");

        awaitList(shopper.token(), OrderBucket.SHIPPED, placed.id(), true);
        awaitList(shopper.token(), OrderBucket.TO_BE_SHIPPED, placed.id(), false);
    }

    /**
     * A declined card payment undoes the whole checkout.
     * <ul>
     *   <li><b>Given</b> an order placed by card for a product with four in stock</li>
     *   <li><b>When</b> the gateway declines the payment the saga opened</li>
     *   <li><b>Then</b> confirming returns 402 {@code PAYMENT_DECLINED}, and the saga's compensating steps
     *       cancel the order and put the reserved units back, leaving the order in neither active tab</li>
     * </ul>
     */
    @Test
    void compensatesTheWholeCheckoutWhenThePaymentIsDeclined() {
        Shopper shopper = shopper();
        ProductView product = createProduct("Silk Scarf", "2200.00", 4, shopper.token());
        send(HttpMethod.POST, "/api/v1/cart/items",
                Map.of("productId", product.id(), "quantity", 1), shopper.token()).expectStatus().isOk();

        OrderView placed = send(HttpMethod.POST, "/api/v1/orders", Map.of(
                        "paymentMethod", "CARD",
                        "address", Map.of("fullName", "Aarav Mehta", "phone", "+919876543210",
                                "line1", "12 Chandni Chowk", "city", "New Delhi", "state", "Delhi",
                                "postalCode", "110006")),
                shopper.token())
                .expectStatus().isCreated()
                .expectBody(OrderView.class).returnResult().getResponseBody();

        // The saga reserves the stock and opens the payment the client has to complete.
        OrderView awaitingPayment = awaitOrder(placed.id(), shopper.token(), o -> o.paymentId() != null);
        assertThat(awaitingPayment.status()).isEqualTo(OrderStatus.PENDING_PAYMENT.name());
        awaitStock(product.id(), 3);

        // Make the gateway refuse this attempt: its documented test hook is a reference containing "decline".
        db.sql("UPDATE payments SET provider_reference = :ref WHERE id = :id")
                .bind("ref", "sim_decline_" + awaitingPayment.paymentId())
                .bind("id", UUID.fromString(awaitingPayment.paymentId()))
                .fetch().rowsUpdated().block();

        send(HttpMethod.POST, "/api/v1/payments/" + awaitingPayment.paymentId() + "/confirm", null,
                shopper.token())
                .expectStatus().isEqualTo(402)
                .expectBody().jsonPath("$.code").isEqualTo("PAYMENT_DECLINED");

        OrderView cancelled = awaitOrder(placed.id(), shopper.token(),
                o -> OrderStatus.CANCELLED.name().equals(o.status()));
        assertThat(cancelled.bucket()).isEqualTo(OrderBucket.CANCELLED.name());
        assertThat(cancelled.cancelReason()).isEqualTo("PAYMENT_FAILED");
        awaitStock(product.id(), 4);

        awaitList(shopper.token(), OrderBucket.TO_BE_SHIPPED, placed.id(), false);
        awaitList(shopper.token(), OrderBucket.SHIPPED, placed.id(), false);
    }

    /**
     * The provider's callback settles a payment with no session at all.
     * <ul>
     *   <li><b>Given</b> an order with an open card payment</li>
     *   <li><b>When</b> a signed callback for it arrives, and then an unsigned one</li>
     *   <li><b>Then</b> the signed callback is accepted without a token or CSRF header and confirms the
     *       order, while the unsigned one is rejected with 401 {@code WEBHOOK_SIGNATURE_INVALID} - the
     *       signature is the only thing authenticating that route</li>
     * </ul>
     */
    @Test
    void settlesAPaymentFromASignedProviderCallback() {
        Shopper shopper = shopper();
        ProductView product = createProduct("Brass Planter", "1750.00", 3, shopper.token());
        send(HttpMethod.POST, "/api/v1/cart/items",
                Map.of("productId", product.id(), "quantity", 1), shopper.token()).expectStatus().isOk();
        OrderView placed = send(HttpMethod.POST, "/api/v1/orders", Map.of(
                        "paymentMethod", "UPI",
                        "address", Map.of("fullName", "Aarav Mehta", "phone", "+919876543210",
                                "line1", "12 Chandni Chowk", "city", "New Delhi", "state", "Delhi",
                                "postalCode", "110006")),
                shopper.token())
                .expectStatus().isCreated()
                .expectBody(OrderView.class).returnResult().getResponseBody();
        OrderView awaitingPayment = awaitOrder(placed.id(), shopper.token(), o -> o.paymentId() != null);

        String payload = "{\"event\":\"payment.authorized\",\"providerReference\":\"sim_"
                + awaitingPayment.paymentId().replace("-", "") + "\"}";
        String signature = ((SimulatedPaymentGateway) gateway).signatureHeader(payload);

        // No bearer token and no CSRF header: a provider has neither.
        client.post().uri("/api/v1/payments/webhook")
                .header("X-Lailo-Signature", signature)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(payload)
                .exchange().expectStatus().isNoContent();

        OrderView confirmed = awaitOrder(placed.id(), shopper.token(),
                o -> OrderStatus.CONFIRMED.name().equals(o.status()));
        assertThat(confirmed.paymentStatus()).isEqualTo(PaymentStatus.AUTHORIZED.name());

        client.post().uri("/api/v1/payments/webhook")
                .header("X-Lailo-Signature", "deadbeef")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(payload)
                .exchange().expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.code").isEqualTo("WEBHOOK_SIGNATURE_INVALID");
    }

    /**
     * Nobody reaches a cart, a wishlist or an order without signing in.
     * <ul>
     *   <li><b>Given</b> no access token</li>
     *   <li><b>When</b> the cart, the wishlist and the orders list are requested</li>
     *   <li><b>Then</b> each is 401, because none of them is public the way the catalogue is</li>
     * </ul>
     */
    @Test
    void keepsPersonalListsBehindSignIn() {
        client.get().uri("/api/v1/cart").exchange().expectStatus().isUnauthorized();
        client.get().uri("/api/v1/wishlist").exchange().expectStatus().isUnauthorized();
        client.get().uri("/api/v1/orders").exchange().expectStatus().isUnauthorized();
        client.get().uri("/api/v1/orders?bucket=SHIPPED").exchange().expectStatus().isUnauthorized();
    }

    /**
     * Polls the orders list until an order is present in, or absent from, a tab.
     *
     * @param token the caller's bearer token
     * @param bucket the tab to read
     * @param orderId the order to look for
     * @param expectPresent whether the order should be in that tab
     * @throws AssertionError if the tab never reaches that state
     */
    private void awaitList(String token, OrderBucket bucket, String orderId, boolean expectPresent) {
        Instant deadline = Instant.now().plus(PROJECTION_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            boolean present = client.get().uri("/api/v1/orders?bucket=" + bucket.name())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .exchange().expectStatus().isOk()
                    .expectBody(com.lailo.application.features.order.application.query.OrderPage.class)
                    .returnResult().getResponseBody()
                    .items().stream().anyMatch(o -> o.id().equals(orderId));
            if (present == expectPresent) {
                return;
            }
            sleep();
        }
        throw new AssertionError("Order " + orderId + (expectPresent ? " never appeared in " : " never left ")
                + bucket);
    }
}
