package com.lailo.application.features.order.application.query;

/**
 * How many orders sit in each tab. Returned with every page so the UI can label "To be shipped (2)" without
 * a second round trip, and so the counts always match the page they arrived with.
 *
 * @param toBeShipped orders still in the warehouse
 * @param shipped orders with the courier or already delivered
 * @param cancelled orders called off
 */
public record OrderCounts(long toBeShipped, long shipped, long cancelled) {
}
