package com.lailo.application.features.product.infrastructure.messaging;

import com.lailo.application.features.product.domain.event.ProductEvents;
import com.lailo.application.features.product.infrastructure.readmodel.ProductProjector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** Keeps the MongoDB catalogue in sync with the Postgres product write model. */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductEventsListener {

    private final JsonMapper jsonMapper;
    private final ProductProjector projector;

    /**
     * Routes a record by its {@code eventType} header to the projector and waits for the Mongo write. Unknown
     * types are ignored; failures are retried by the listener container.
     *
     * @param record the Kafka record (key = product id, value = event JSON)
     */
    @KafkaListener(topics = ProductEvents.TOPIC, groupId = "lailo-product-catalog-projector")
    public void onMessage(ConsumerRecord<String, String> record) {
        var header = record.headers().lastHeader("eventType");
        String type = header == null ? "" : new String(header.value(), StandardCharsets.UTF_8);
        Mono<Void> work = switch (type) {
            case ProductEvents.PRODUCT_CREATED ->
                    projector.on(jsonMapper.readValue(record.value(), ProductEvents.ProductCreated.class));
            case ProductEvents.PRODUCT_UPDATED ->
                    projector.on(jsonMapper.readValue(record.value(), ProductEvents.ProductUpdated.class));
            case ProductEvents.PRODUCT_ARCHIVED ->
                    projector.on(jsonMapper.readValue(record.value(), ProductEvents.ProductArchived.class));
            case ProductEvents.PRODUCT_MEDIA_ADDED ->
                    projector.on(jsonMapper.readValue(record.value(), ProductEvents.ProductMediaAdded.class));
            case ProductEvents.PRODUCT_STOCK_CHANGED ->
                    projector.on(jsonMapper.readValue(record.value(), ProductEvents.ProductStockChanged.class));
            case ProductEvents.PRODUCT_MEDIA_REMOVED ->
                    projector.on(jsonMapper.readValue(record.value(), ProductEvents.ProductMediaRemoved.class));
            default -> Mono.empty(); // other consumers (search indexing, analytics) handle the rest
        };
        // Listener threads are dedicated (not event-loop), so blocking here is correct; failures are retried by the container.
        work.block(Duration.ofSeconds(10));
    }
}
