package com.lailo.application.features.order.infrastructure.notification;

import com.lailo.application.common.cqrs.QueryBus;
import com.lailo.application.common.notification.NotificationDispatcher;
import com.lailo.application.common.web.ApiException;
import com.lailo.application.features.auth.application.query.GetUserContactQuery;
import com.lailo.application.features.auth.application.query.UserContact;
import com.lailo.application.features.order.domain.event.OrderEvents;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

/**
 * Emails and texts the shopper when their order is placed.
 *
 * <p>It is a consumer rather than a step inside {@code PlaceOrderHandler} for two reasons. A provider being
 * slow or down must not fail - or hold a transaction open across - the request that places the order; and
 * sending from the committed event means a notification is never sent for an order that rolled back, nor
 * missed for one that committed.</p>
 *
 * <p>Its own consumer group, separate from the projector and the saga, so a notification problem never
 * stalls the orders list or the payment flow.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderNotificationListener {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final JsonMapper jsonMapper;
    private final QueryBus queryBus;
    private final NotificationDispatcher dispatcher;
    private final OrderNotificationTemplates templates;

    /**
     * Routes a record by its {@code eventType} header. Only {@code OrderPlaced} is notified on today; the
     * later stages (shipped, delivered) belong here too and need no other change.
     *
     * @param record the Kafka record (key = order id, value = event JSON)
     */
    @KafkaListener(topics = OrderEvents.TOPIC, groupId = "lailo-order-notifications")
    public void onMessage(ConsumerRecord<String, String> record) {
        var header = record.headers().lastHeader("eventType");
        String type = header == null ? "" : new String(header.value(), StandardCharsets.UTF_8);
        Mono<Void> work = OrderEvents.ORDER_PLACED.equals(type)
                ? notifyPlaced(jsonMapper.readValue(record.value(), OrderEvents.OrderPlaced.class))
                : Mono.empty();
        // Listener threads are dedicated (not event-loop), so blocking here is correct; a failure is retried
        // by the container, and the delivery claim keeps a retry from sending twice.
        work.block(TIMEOUT);
    }

    /**
     * Looks up where to reach the shopper, renders the notification and sends it.
     *
     * @param e the event
     * @return completion signal
     */
    private Mono<Void> notifyPlaced(OrderEvents.OrderPlaced e) {
        Mono<UserContact> contact = queryBus.ask(new GetUserContactQuery(UUID.fromString(e.userId())));
        return contact
                .flatMap(found -> {
                    if (!found.isReachable()) {
                        log.warn("Order {} placed by a user with no email or phone - nothing to notify",
                                e.orderNumber());
                        return Mono.<Void>empty();
                    }
                    return dispatcher.dispatch(templates.orderPlaced(e, found.toRecipient(e.shippingRecipient())));
                })
                .onErrorResume(ApiException.class, ex -> {
                    // A missing account is not retryable, so swallow it rather than block the partition.
                    log.warn("Cannot notify for order {}: {}", e.orderNumber(), ex.getCode());
                    return Mono.empty();
                });
    }
}
