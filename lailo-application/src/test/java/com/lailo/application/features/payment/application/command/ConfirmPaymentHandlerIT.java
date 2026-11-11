package com.lailo.application.features.payment.application.command;

import com.lailo.application.common.cqrs.CommandBus;
import com.lailo.application.common.web.ApiException;
import com.lailo.application.features.auth.domain.User;
import com.lailo.application.features.auth.infrastructure.persistence.UserRepository;
import com.lailo.application.features.order.domain.Order;
import com.lailo.application.features.order.infrastructure.persistence.OrderItemRepository;
import com.lailo.application.features.order.infrastructure.persistence.OrderRepository;
import com.lailo.application.features.payment.application.query.PaymentView;
import com.lailo.application.features.payment.domain.Payment;
import com.lailo.application.features.payment.domain.PaymentMethod;
import com.lailo.application.features.payment.domain.PaymentStatus;
import com.lailo.application.features.payment.infrastructure.persistence.PaymentRepository;
import com.lailo.application.features.product.application.command.CreateProductCommand;
import com.lailo.application.support.CommerceFixtures;
import com.lailo.application.support.IntegrationTest;
import com.lailo.application.support.ProductFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.r2dbc.core.DatabaseClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Settling payments against real PostgreSQL and the simulated gateway.
 *
 * <p>The gateway is not mocked: it is the project's development adapter, and a reference containing
 * {@code decline} is its documented way to force a refusal. That keeps the failure path exercised through
 * the same code a real provider would drive.</p>
 */
class ConfirmPaymentHandlerIT extends IntegrationTest {

    @Autowired CommandBus commandBus;
    @Autowired UserRepository users;
    @Autowired OrderRepository orders;
    @Autowired OrderItemRepository orderItems;
    @Autowired PaymentRepository payments;
    @Autowired DatabaseClient db;

    /**
     * Stores an order to pay for, since payments have a foreign key to one.
     *
     * @return the saved order
     */
    private Order order() {
        User shopper = CommerceFixtures.signedInUser(users, uniqueEmail());
        UUID productId = UUID.fromString(commandBus.dispatch(new CreateProductCommand(
                ProductFixtures.details("Payable Product", ProductFixtures.uniqueCategory())))
                .block().id());
        return CommerceFixtures.confirmedOrder(orders, orderItems, shopper.getId(), productId);
    }

    /**
     * Stores a pending attempt with a chosen gateway reference, which is what decides the outcome.
     *
     * @param order the order being paid for
     * @param reference the gateway reference; include {@code decline} to force a refusal
     * @return the saved attempt
     */
    private Payment pendingPayment(Order order, String reference) {
        return payments.save(Payment.initiate(UUID.randomUUID(), order.getId(), order.getUserId(),
                        new BigDecimal("999.00"), "INR", PaymentMethod.CARD, "simulated", Instant.now())
                .withProviderReference(reference)).block();
    }

    /**
     * Reads the payment event types recorded in the outbox for one order, oldest first.
     *
     * @param orderId the order
     * @return the recorded event types
     */
    private List<String> paymentEvents(UUID orderId) {
        return db.sql("""
                        SELECT event_type FROM outbox_messages
                        WHERE aggregate_type = 'Payment' AND aggregate_id = :id ORDER BY created_at
                        """)
                .bind("id", orderId.toString()).map(r -> r.get(0, String.class)).all().collectList().block();
    }

    /**
     * An approved attempt is recorded and announced.
     * <ul>
     *   <li><b>Given</b> a pending card payment the gateway will approve</li>
     *   <li><b>When</b> it is confirmed</li>
     *   <li><b>Then</b> it becomes {@code AUTHORIZED} with an authorisation time and one
     *       {@code PaymentAuthorized} event is in the outbox - which is what the saga turns into a confirmed
     *       order</li>
     * </ul>
     */
    @Test
    void recordsAndAnnouncesAnApprovedPayment() {
        Order order = order();
        Payment pending = pendingPayment(order, "sim_ok_" + UUID.randomUUID());

        PaymentView settled = commandBus.dispatch(
                new ConfirmPaymentCommand(pending.getId(), order.getUserId())).block();

        assertThat(settled.status()).isEqualTo(PaymentStatus.AUTHORIZED.name());
        assertThat(settled.authorizedAt()).isNotNull();
        assertThat(payments.findById(pending.getId()).block().isAuthorized()).isTrue();
        assertThat(paymentEvents(order.getId())).containsExactly("PaymentAuthorized");
    }

    /**
     * A decline is committed before the error is reported.
     * <ul>
     *   <li><b>Given</b> a pending payment the gateway will refuse</li>
     *   <li><b>When</b> it is confirmed</li>
     *   <li><b>Then</b> the caller gets {@code PAYMENT_DECLINED}, and the row is {@code FAILED} with a
     *       {@code PaymentFailed} event in the outbox - the failure must survive the error, or the saga would
     *       never learn to cancel the order and release its stock</li>
     * </ul>
     */
    @Test
    void commitsADeclineBeforeReportingIt() {
        Order order = order();
        Payment pending = pendingPayment(order, "sim_decline_" + UUID.randomUUID());

        assertThatThrownBy(() -> commandBus
                .dispatch(new ConfirmPaymentCommand(pending.getId(), order.getUserId())).block())
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("PAYMENT_DECLINED");

        Payment stored = payments.findById(pending.getId()).block();
        assertThat(stored.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(stored.getFailureReason()).isNotBlank();
        assertThat(paymentEvents(order.getId())).containsExactly("PaymentFailed");
    }

    /**
     * Confirming an already authorised payment is a no-op, not an error.
     * <ul>
     *   <li><b>Given</b> a payment that has already been authorised</li>
     *   <li><b>When</b> it is confirmed again, as the client and the provider's webhook both will</li>
     *   <li><b>Then</b> the same authorised payment is returned and no second event is published, so the
     *       order is never confirmed twice</li>
     * </ul>
     */
    @Test
    void confirmingTwiceIsIdempotent() {
        Order order = order();
        Payment pending = pendingPayment(order, "sim_ok_" + UUID.randomUUID());
        commandBus.dispatch(new ConfirmPaymentCommand(pending.getId(), order.getUserId())).block();

        PaymentView again = commandBus.dispatch(
                new ConfirmPaymentCommand(pending.getId(), order.getUserId())).block();

        assertThat(again.status()).isEqualTo(PaymentStatus.AUTHORIZED.name());
        assertThat(paymentEvents(order.getId())).containsExactly("PaymentAuthorized");
    }

    /**
     * A failed attempt cannot be retried in place.
     * <ul>
     *   <li><b>Given</b> a payment that has already been declined</li>
     *   <li><b>When</b> it is confirmed again</li>
     *   <li><b>Then</b> it fails with {@code PAYMENT_NOT_PENDING}: a retry is a new attempt, which keeps the
     *       declined one on record for reconciliation</li>
     * </ul>
     */
    @Test
    void refusesToReopenAFailedAttempt() {
        Order order = order();
        Payment failed = payments.save(Payment.initiate(UUID.randomUUID(), order.getId(), order.getUserId(),
                        new BigDecimal("999.00"), "INR", PaymentMethod.CARD, "simulated", Instant.now())
                .fail("declined by bank", Instant.now())).block();

        assertThatThrownBy(() -> commandBus
                .dispatch(new ConfirmPaymentCommand(failed.getId(), order.getUserId())).block())
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("PAYMENT_NOT_PENDING");
    }

    /**
     * One shopper cannot settle another's payment.
     * <ul>
     *   <li><b>Given</b> a pending payment and an unrelated shopper</li>
     *   <li><b>When</b> that shopper confirms it</li>
     *   <li><b>Then</b> it fails with {@code PAYMENT_NOT_FOUND} - the same answer as a missing id - and the
     *       payment is untouched</li>
     * </ul>
     */
    @Test
    void hidesAnotherShoppersPayment() {
        Order order = order();
        Payment pending = pendingPayment(order, "sim_ok_" + UUID.randomUUID());
        User stranger = CommerceFixtures.signedInUser(users, uniqueEmail());

        assertThatThrownBy(() -> commandBus
                .dispatch(new ConfirmPaymentCommand(pending.getId(), stranger.getId())).block())
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("PAYMENT_NOT_FOUND");

        assertThat(payments.findById(pending.getId()).block().isPending()).isTrue();
    }

    /**
     * Cash on delivery never touches the gateway.
     * <ul>
     *   <li><b>Given</b> an order to be paid on delivery</li>
     *   <li><b>When</b> the payment is initiated</li>
     *   <li><b>Then</b> it is already {@code AUTHORIZED} with no checkout link, and both the initiation and
     *       the authorisation are in the outbox - so the saga confirms the order without waiting for anyone
     *       </li>
     * </ul>
     */
    @Test
    void authorizesCashOnDeliveryImmediately() {
        Order order = order();

        PaymentView view = commandBus.dispatch(InitiatePaymentCommand.builder()
                .orderId(order.getId())
                .orderNumber(order.getOrderNumber())
                .userId(order.getUserId())
                .amount(new BigDecimal("999.00"))
                .currency("INR")
                .method(PaymentMethod.COD)
                .build()).block();

        assertThat(view.status()).isEqualTo(PaymentStatus.AUTHORIZED.name());
        assertThat(view.checkoutUrl()).isNull();
        assertThat(paymentEvents(order.getId())).containsExactly("PaymentInitiated", "PaymentAuthorized");
    }

    /**
     * An online payment is opened at the gateway before anything is stored.
     * <ul>
     *   <li><b>Given</b> an order to be paid by UPI</li>
     *   <li><b>When</b> the payment is initiated</li>
     *   <li><b>Then</b> it is {@code PENDING} with a gateway reference and a checkout link for the client,
     *       and only the initiation has been announced</li>
     * </ul>
     */
    @Test
    void opensAnOnlinePaymentAtTheGateway() {
        Order order = order();

        PaymentView view = commandBus.dispatch(InitiatePaymentCommand.builder()
                .orderId(order.getId())
                .orderNumber(order.getOrderNumber())
                .userId(order.getUserId())
                .amount(new BigDecimal("999.00"))
                .currency("INR")
                .method(PaymentMethod.UPI)
                .build()).block();

        assertThat(view.status()).isEqualTo(PaymentStatus.PENDING.name());
        assertThat(view.provider()).isEqualTo("simulated");
        assertThat(view.providerReference()).startsWith("sim_");
        assertThat(view.checkoutUrl()).contains(view.providerReference());
        assertThat(paymentEvents(order.getId())).containsExactly("PaymentInitiated");
    }

    /**
     * Refunding is safe to repeat, because it runs as a compensating action.
     * <ul>
     *   <li><b>Given</b> an authorised payment</li>
     *   <li><b>When</b> it is refunded, then refunded again</li>
     *   <li><b>Then</b> the first call returns the money and the second reports there was nothing to return,
     *       and only one {@code PaymentRefunded} event exists</li>
     * </ul>
     */
    @Test
    void refundingTwiceReturnsTheMoneyOnce() {
        Order order = order();
        Payment pending = pendingPayment(order, "sim_ok_" + UUID.randomUUID());
        commandBus.dispatch(new ConfirmPaymentCommand(pending.getId(), order.getUserId())).block();

        Boolean first = commandBus.dispatch(new RefundPaymentCommand(pending.getId())).block();
        Boolean second = commandBus.dispatch(new RefundPaymentCommand(pending.getId())).block();

        assertThat(first).isTrue();
        assertThat(second).isFalse();
        assertThat(payments.findById(pending.getId()).block().getStatus())
                .isEqualTo(PaymentStatus.REFUNDED);
        assertThat(paymentEvents(order.getId())).containsExactly("PaymentAuthorized", "PaymentRefunded");
    }
}
