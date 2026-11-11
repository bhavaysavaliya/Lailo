package com.lailo.application.features.order.application.command;

import com.lailo.application.common.cqrs.Command;
import com.lailo.application.features.order.domain.OrderStatus;
import lombok.Builder;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Moves an order along the fulfilment pipeline: packed, shipped or delivered.
 *
 * <p>One command rather than three, because they differ only in the target state and in whether tracking
 * details come with it. The legal moves are enforced by the handler against
 * {@link OrderStatus}, so this cannot be used to skip a step.</p>
 *
 * @param orderId the order to move
 * @param target the state to move to: {@code PACKED}, {@code SHIPPED} or {@code DELIVERED}
 * @param courierName carrier handling the parcel; required for {@code SHIPPED}
 * @param trackingNumber carrier's tracking reference; required for {@code SHIPPED}
 * @param expectedDeliveryDate promised delivery date, or {@code null}
 */
@Builder
public record AdvanceOrderFulfilmentCommand(UUID orderId, OrderStatus target, String courierName,
                                            String trackingNumber, LocalDate expectedDeliveryDate)
        implements Command<Boolean> {

    /** Rejects a target that is not part of the fulfilment pipeline. */
    public AdvanceOrderFulfilmentCommand {
        if (orderId == null) {
            throw new IllegalArgumentException("orderId is required");
        }
        if (target != OrderStatus.PACKED && target != OrderStatus.SHIPPED && target != OrderStatus.DELIVERED) {
            throw new IllegalArgumentException("target must be PACKED, SHIPPED or DELIVERED");
        }
    }
}
