package com.lailo.application.features.order.infrastructure.messaging;

import com.lailo.application.features.order.domain.event.OrderEvents;
import com.lailo.application.features.order.infrastructure.readmodel.OrderProjector;
import com.lailo.application.features.payment.domain.event.PaymentEvents;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Keeps the MongoDB orders read model in sync with the order and payment write models.
 *
 * <p>It subscribes to both topics, because the thing a shopper wants on one screen - "my order, and whether
 * it is paid" - is spread over two aggregates. Both streams are keyed by order id, so the events for one
 * order arrive in order even though they come from two producers.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventsListener {

    private final JsonMapper jsonMapper;
    private final OrderProjector projector;

    /**
     * Routes a record by its {@code eventType} header to the projector and waits for the Mongo write.
     * Unknown types are ignored; failures are retried by the listener container.
     *
     * @param record the Kafka record (key = order id, value = event JSON)
     */
    @KafkaListener(topics = {OrderEvents.TOPIC, PaymentEvents.TOPIC}, groupId = "lailo-order-projector")
    public void onMessage(ConsumerRecord<String, String> record) {
        var header = record.headers().lastHeader("eventType");
        String type = header == null ? "" : new String(header.value(), StandardCharsets.UTF_8);
        Mono<Void> work = switch (type) {
            case OrderEvents.ORDER_PLACED ->
                    projector.on(jsonMapper.readValue(record.value(), OrderEvents.OrderPlaced.class));
            case OrderEvents.ORDER_CONFIRMED, OrderEvents.ORDER_PACKED, OrderEvents.ORDER_DELIVERED ->
                    projector.on(jsonMapper.readValue(record.value(), OrderEvents.OrderStatusChanged.class));
            case OrderEvents.ORDER_SHIPPED ->
                    projector.on(jsonMapper.readValue(record.value(), OrderEvents.OrderShipped.class));
            case OrderEvents.ORDER_CANCELLED ->
                    projector.on(jsonMapper.readValue(record.value(), OrderEvents.OrderCancelled.class));
            case PaymentEvents.PAYMENT_INITIATED ->
                    projector.on(jsonMapper.readValue(record.value(), PaymentEvents.PaymentInitiated.class));
            case PaymentEvents.PAYMENT_AUTHORIZED ->
                    projector.on(jsonMapper.readValue(record.value(), PaymentEvents.PaymentAuthorized.class));
            case PaymentEvents.PAYMENT_FAILED ->
                    projector.on(jsonMapper.readValue(record.value(), PaymentEvents.PaymentFailed.class));
            case PaymentEvents.PAYMENT_REFUNDED ->
                    projector.on(jsonMapper.readValue(record.value(), PaymentEvents.PaymentRefunded.class));
            default -> Mono.empty(); // other consumers (invoicing, analytics) handle the rest
        };
        work.block(Duration.ofSeconds(10));
    }
}
