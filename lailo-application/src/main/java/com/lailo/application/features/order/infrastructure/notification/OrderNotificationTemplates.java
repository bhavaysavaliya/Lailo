package com.lailo.application.features.order.infrastructure.notification;

import com.lailo.application.common.notification.NotificationProperties;
import com.lailo.application.common.notification.NotificationRequest;
import com.lailo.application.common.notification.Recipient;
import com.lailo.application.features.order.domain.event.OrderEvents;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Renders order notifications. Kept apart from the listener so the wording can be tested without Kafka, and
 * apart from the channels so the same content goes out by email and text.
 */
@Component
@RequiredArgsConstructor
public class OrderNotificationTemplates {

    /** Lailo sells in India, so dates and times are shown in IST whatever the server runs on. */
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter PLACED_ON =
            DateTimeFormatter.ofPattern("d MMMM yyyy, h:mm a", Locale.ENGLISH).withZone(IST);

    private final NotificationProperties properties;

    /**
     * Builds the "order placed" notification.
     *
     * @param e the event that was consumed; its id becomes the dedupe key, so a redelivery sends nothing
     * @param recipient where to send it
     * @return the rendered request, ready for the dispatcher
     */
    public NotificationRequest orderPlaced(OrderEvents.OrderPlaced e, Recipient recipient) {
        String brand = properties.brandName();
        String trackingLink = properties.link("/orders/" + e.orderId());
        return NotificationRequest.builder()
                .dedupeKey(e.eventId())
                .recipient(recipient)
                .subject(brand + " order " + e.orderNumber() + " is placed")
                .emailBody(emailBody(e, brand, trackingLink))
                .htmlBody(htmlBody(e, brand, trackingLink))
                .smsBody(smsBody(e, brand, trackingLink))
                .build();
    }

    /**
     * The plain-text email, which is what most clients and every screen reader actually render.
     *
     * @param e the event
     * @param brand brand name
     * @param trackingLink link back to the order
     * @return the body
     */
    private String emailBody(OrderEvents.OrderPlaced e, String brand, String trackingLink) {
        StringBuilder lines = new StringBuilder();
        e.items().forEach(item -> lines.append("  ")
                .append(item.quantity()).append(" x ").append(item.productName())
                .append("   ").append(money(e.currency(), item.lineTotal()))
                .append(System.lineSeparator()));

        return """
                Hi %s,

                Thanks for your order. We have it, and we'll tell you the moment it ships.

                Order %s
                Placed on %s

                %s
                Items      %s
                Delivery   %s
                Total      %s

                Delivering to
                %s
                %s

                Track your order: %s

                Questions? Reply to this email or write to %s.

                - %s
                """.formatted(
                e.shippingRecipient(),
                e.orderNumber(),
                PLACED_ON.format(e.occurredAt()),
                lines.toString().stripTrailing(),
                money(e.currency(), e.itemsTotal()),
                e.shippingFee().signum() == 0 ? "Free" : money(e.currency(), e.shippingFee()),
                money(e.currency(), e.totalAmount()),
                e.shippingRecipient(),
                e.shippingAddressLine(),
                trackingLink,
                properties.supportEmail(),
                brand);
    }

    /**
     * The HTML email. Deliberately plain inline markup: transactional mail has to survive clients that strip
     * stylesheets, and a receipt is a table, not a layout exercise.
     *
     * @param e the event
     * @param brand brand name
     * @param trackingLink link back to the order
     * @return the body
     */
    private String htmlBody(OrderEvents.OrderPlaced e, String brand, String trackingLink) {
        StringBuilder rows = new StringBuilder();
        e.items().forEach(item -> rows
                .append("<tr><td style=\"padding:6px 0\">")
                .append(escape(item.quantity() + " × " + item.productName()))
                .append("</td><td style=\"padding:6px 0;text-align:right\">")
                .append(escape(money(e.currency(), item.lineTotal())))
                .append("</td></tr>"));

        return """
                <div style="font-family:system-ui,sans-serif;color:#1D1A24;max-width:560px">
                  <h1 style="font-size:20px;color:#4E2A84">Your order is placed</h1>
                  <p>Hi %s, thanks for your order. We'll tell you the moment it ships.</p>
                  <p><strong>Order %s</strong><br>Placed on %s</p>
                  <table style="width:100%%;border-collapse:collapse;font-size:14px">%s
                    <tr><td style="padding:6px 0;border-top:1px solid #E6E1EF">Items</td>
                        <td style="padding:6px 0;border-top:1px solid #E6E1EF;text-align:right">%s</td></tr>
                    <tr><td style="padding:6px 0">Delivery</td>
                        <td style="padding:6px 0;text-align:right">%s</td></tr>
                    <tr><td style="padding:6px 0;font-weight:600">Total</td>
                        <td style="padding:6px 0;text-align:right;font-weight:600">%s</td></tr>
                  </table>
                  <p style="font-size:14px"><strong>Delivering to</strong><br>%s<br>%s</p>
                  <p><a href="%s" style="color:#4E2A84">Track your order</a></p>
                  <p style="font-size:12px;color:#6B6478">%s</p>
                </div>
                """.formatted(
                escape(e.shippingRecipient()),
                escape(e.orderNumber()),
                escape(PLACED_ON.format(e.occurredAt())),
                rows,
                escape(money(e.currency(), e.itemsTotal())),
                e.shippingFee().signum() == 0 ? "Free" : escape(money(e.currency(), e.shippingFee())),
                escape(money(e.currency(), e.totalAmount())),
                escape(e.shippingRecipient()),
                escape(e.shippingAddressLine()),
                escape(trackingLink),
                escape(brand));
    }

    /**
     * The text message. Short on purpose, and written with plain ASCII: a rupee sign would push the message
     * into UCS-2 encoding, halving how much fits in one billed segment.
     *
     * @param e the event
     * @param brand brand name
     * @param trackingLink link back to the order
     * @return the body
     */
    private String smsBody(OrderEvents.OrderPlaced e, String brand, String trackingLink) {
        int units = e.totalQuantity();
        return "%s: order %s placed. %d item%s, %s. Track: %s".formatted(
                brand, e.orderNumber(), units, units == 1 ? "" : "s",
                money(e.currency(), e.totalAmount()), trackingLink);
    }

    /**
     * Formats an amount the way it is written in India, e.g. {@code INR 1,299.00}.
     *
     * @param currency ISO 4217 code
     * @param amount the amount
     * @return the formatted amount
     */
    private static String money(String currency, BigDecimal amount) {
        return currency + " " + String.format(Locale.ENGLISH, "%,.2f", amount);
    }

    /**
     * Escapes text that goes into the HTML body, so a product name containing a bracket cannot break - or
     * inject into - the markup.
     *
     * @param value the text
     * @return the escaped text
     */
    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
