package com.lailo.application.features.order.application.query;

import com.lailo.application.features.order.domain.Order;
import com.lailo.application.features.order.domain.OrderBucket;
import com.lailo.application.features.order.domain.OrderItem;
import com.lailo.application.features.order.infrastructure.readmodel.OrderDocument;
import com.lailo.application.features.order.infrastructure.readmodel.OrderItemDocument;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/** Builds order views from either side of the CQRS split, so both produce byte-identical JSON. */
@Component
public class OrderViewMapper {

    /**
     * Maps the write-model aggregate, which is how a command returns the state it just wrote and how the
     * read falls back while the projection catches up.
     *
     * @param order the aggregate
     * @param items the order's lines
     * @param paymentStatus status of the attempt in play, or {@code null} when none exists yet
     * @return the view
     */
    public OrderView fromAggregate(Order order, List<OrderItem> items, String paymentStatus) {
        List<OrderItemView> lines = items.stream()
                .sorted(Comparator.comparing(OrderItem::getProductName,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .map(item -> OrderItemView.builder()
                        .productId(item.getProductId().toString())
                        .name(item.getProductName())
                        .slug(item.getProductSlug())
                        .imageUrl(item.getImageUrl())
                        .quantity(item.getQuantity())
                        .unitPrice(item.getUnitPrice())
                        .lineTotal(item.getLineTotal())
                        .currency(item.getCurrency())
                        .build())
                .toList();
        return OrderView.builder()
                .id(order.getId().toString())
                .orderNumber(order.getOrderNumber())
                .status(order.getStatus().name())
                .bucket(order.bucket().name())
                .items(lines)
                .totalQuantity(lines.stream().mapToInt(OrderItemView::quantity).sum())
                .itemsTotal(order.getItemsTotal())
                .shippingFee(order.getShippingFee())
                .totalAmount(order.getTotalAmount())
                .currency(order.getCurrency())
                .paymentMethod(order.getPaymentMethod().name())
                .paymentId(order.getPaymentId() == null ? null : order.getPaymentId().toString())
                .paymentStatus(paymentStatus)
                .shippingRecipient(order.getShippingAddress().fullName())
                .shippingAddressLine(order.getShippingAddress().singleLine())
                .courierName(order.getCourierName())
                .trackingNumber(order.getTrackingNumber())
                .expectedDeliveryDate(order.getExpectedDeliveryDate())
                .placedAt(order.getPlacedAt())
                .confirmedAt(order.getConfirmedAt())
                .packedAt(order.getPackedAt())
                .shippedAt(order.getShippedAt())
                .deliveredAt(order.getDeliveredAt())
                .cancelledAt(order.getCancelledAt())
                .cancelReason(order.getCancelReason() == null ? null : order.getCancelReason().name())
                .build();
    }

    /**
     * Maps a projected order document.
     *
     * @param d the read-model document
     * @return the view
     */
    public OrderView fromDocument(OrderDocument d) {
        List<OrderItemView> lines = d.getItems() == null ? List.of() : d.getItems().stream()
                .map(OrderViewMapper::toView)
                .toList();
        return OrderView.builder()
                .id(d.getId())
                .orderNumber(d.getOrderNumber())
                .status(d.getStatus())
                .bucket(d.getBucket() == null ? OrderBucket.ALL.name() : d.getBucket())
                .items(lines)
                .totalQuantity(d.getTotalQuantity() == null ? 0 : d.getTotalQuantity())
                .itemsTotal(d.getItemsTotal())
                .shippingFee(d.getShippingFee())
                .totalAmount(d.getTotalAmount())
                .currency(d.getCurrency())
                .paymentMethod(d.getPaymentMethod())
                .paymentId(d.getPaymentId())
                .paymentStatus(d.getPaymentStatus())
                .shippingRecipient(d.getShippingRecipient())
                .shippingAddressLine(d.getShippingAddressLine())
                .courierName(d.getCourierName())
                .trackingNumber(d.getTrackingNumber())
                .expectedDeliveryDate(d.getExpectedDeliveryDate())
                .placedAt(d.getPlacedAt())
                .confirmedAt(d.getConfirmedAt())
                .packedAt(d.getPackedAt())
                .shippedAt(d.getShippedAt())
                .deliveredAt(d.getDeliveredAt())
                .cancelledAt(d.getCancelledAt())
                .cancelReason(d.getCancelReason())
                .build();
    }

    /**
     * Maps one projected line.
     *
     * @param item the line document
     * @return the line view
     */
    private static OrderItemView toView(OrderItemDocument item) {
        return OrderItemView.builder()
                .productId(item.getProductId())
                .name(item.getName())
                .slug(item.getSlug())
                .imageUrl(item.getImageUrl())
                .quantity(item.getQuantity() == null ? 0 : item.getQuantity())
                .unitPrice(item.getUnitPrice())
                .lineTotal(item.getLineTotal())
                .currency(item.getCurrency())
                .build();
    }
}
