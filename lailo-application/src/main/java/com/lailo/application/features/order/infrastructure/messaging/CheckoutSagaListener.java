package com.lailo.application.features.order.infrastructure.messaging;

import com.lailo.application.features.order.application.saga.CheckoutSaga;
import com.lailo.application.features.order.domain.event.OrderEvents;
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
 * Feeds order and payment events to {@link CheckoutSaga}.
 *
 * <p>Its own consumer group, deliberately separate from the projector's: the saga issues commands and takes
 * money, the projector only writes a document, and a problem in one must not hold up the other. Both read
 * the same topics, which is exactly what consumer groups are for.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CheckoutSagaListener {

    /** Generous, because a step can involve the payment gateway. */
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final JsonMapper jsonMapper;
    private final CheckoutSaga saga;

    /**
     * Routes a record by its {@code eventType} header to the saga and waits for the step to finish.
     *
     * @param record the Kafka record (key = order id, value = event JSON)
     */
    @KafkaListener(topics = {OrderEvents.TOPIC, PaymentEvents.TOPIC}, groupId = "lailo-checkout-saga")
    public void onMessage(ConsumerRecord<String, String> record) {
        var header = record.headers().lastHeader("eventType");
        String type = header == null ? "" : new String(header.value(), StandardCharsets.UTF_8);
        Mono<Void> work = switch (type) {
            case OrderEvents.ORDER_PLACED ->
                    saga.on(jsonMapper.readValue(record.value(), OrderEvents.OrderPlaced.class));
            case OrderEvents.ORDER_CANCELLED ->
                    saga.on(jsonMapper.readValue(record.value(), OrderEvents.OrderCancelled.class));
            case PaymentEvents.PAYMENT_INITIATED ->
                    saga.on(jsonMapper.readValue(record.value(), PaymentEvents.PaymentInitiated.class));
            case PaymentEvents.PAYMENT_AUTHORIZED ->
                    saga.on(jsonMapper.readValue(record.value(), PaymentEvents.PaymentAuthorized.class));
            case PaymentEvents.PAYMENT_FAILED ->
                    saga.on(jsonMapper.readValue(record.value(), PaymentEvents.PaymentFailed.class));
            default -> Mono.empty(); // the saga only reacts to the events that drive a step
        };
        // Listener threads are dedicated (not event-loop), so blocking here is correct; the step transitions
        // are the idempotency guard, so a container retry cannot repeat a step that already ran.
        work.block(TIMEOUT);
    }
}
