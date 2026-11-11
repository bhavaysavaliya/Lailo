package com.lailo.application.features.cart.application.query;

import com.lailo.application.common.cqrs.QueryBus;
import com.lailo.application.features.cart.domain.CartItem;
import com.lailo.application.features.cart.infrastructure.persistence.CartItemRepository;
import com.lailo.application.features.product.application.query.GetProductsQuery;
import com.lailo.application.features.product.application.query.ProductMediaView;
import com.lailo.application.features.product.application.query.ProductView;
import com.lailo.application.features.product.domain.Product;
import com.lailo.application.features.product.domain.ProductStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Builds a {@link CartView} from the write model plus a bulk catalogue read.
 *
 * <p>The cart is deliberately not projected into MongoDB. A cart is a single-user aggregate that is read
 * immediately after every write, so the ~1s projection lag would be visible as "I added it and it's not
 * there"; and the only thing a projection could denormalise - name, price, stock - is exactly the part that
 * must be live at checkout. So the lines come from PostgreSQL and the product detail from the catalogue, in
 * two indexed calls that are both read-your-write correct.</p>
 */
@Component
@RequiredArgsConstructor
public class CartReader {

    private final CartItemRepository cartItems;
    private final QueryBus queryBus;

    /**
     * Reads and enriches a cart.
     *
     * @param userId whose cart to read
     * @return the cart, empty rather than absent when the shopper has no lines
     */
    public Mono<CartView> read(UUID userId) {
        return cartItems.findByUserIdOrderByCreatedAt(userId)
                .collectList()
                .flatMap(lines -> lines.isEmpty()
                        ? Mono.just(emptyCart(userId))
                        : enrich(userId, lines));
    }

    /**
     * Joins the stored lines with what the catalogue says about each product.
     *
     * @param userId whose cart this is
     * @param lines the stored lines
     * @return the assembled view
     */
    private Mono<CartView> enrich(UUID userId, List<CartItem> lines) {
        Set<UUID> productIds = lines.stream().map(CartItem::getProductId).collect(Collectors.toSet());
        return queryBus.ask(new GetProductsQuery(productIds))
                .map(products -> {
                    Map<String, ProductView> byId = products.stream()
                            .collect(Collectors.toMap(ProductView::id, Function.identity()));
                    List<CartItemView> items = lines.stream()
                            .map(line -> toView(line, byId.get(line.getProductId().toString())))
                            .toList();
                    return assemble(userId, items);
                });
    }

    /**
     * Maps one line. A product the catalogue no longer knows about is kept in the cart but marked
     * unavailable, so the shopper sees what happened instead of finding a line silently gone.
     *
     * @param line the stored line
     * @param product the catalogue entry, or {@code null} when it no longer exists
     * @return the line view
     */
    private static CartItemView toView(CartItem line, ProductView product) {
        boolean known = product != null;
        BigDecimal unitPrice = known ? product.price() : line.getUnitPrice();
        int stock = known ? product.stockQuantity() : 0;
        boolean available = known
                && ProductStatus.ACTIVE.name().equals(product.status())
                && stock >= line.getQuantity();
        return CartItemView.builder()
                .productId(line.getProductId().toString())
                .name(known ? product.name() : "Unavailable product")
                .slug(known ? product.slug() : null)
                .imageUrl(firstImage(product))
                .quantity(line.getQuantity())
                .unitPrice(unitPrice)
                .unitPriceWhenAdded(line.getUnitPrice())
                .lineTotal(unitPrice.multiply(BigDecimal.valueOf(line.getQuantity())))
                .currency(line.getCurrency())
                .available(available)
                .availableQuantity(stock)
                .addedAt(line.getCreatedAt())
                .build();
    }

    /**
     * Totals the orderable lines. Unavailable lines are excluded, so the figure on screen is what the
     * shopper would actually be charged once they remove them.
     *
     * @param userId whose cart this is
     * @param items the line views
     * @return the cart view
     */
    private static CartView assemble(UUID userId, List<CartItemView> items) {
        BigDecimal total = items.stream()
                .filter(CartItemView::available)
                .map(CartItemView::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return CartView.builder()
                .userId(userId.toString())
                .items(items)
                .itemCount(items.size())
                .totalQuantity(items.stream().mapToInt(CartItemView::quantity).sum())
                .itemsTotal(total)
                .currency(Product.CURRENCY)
                .hasUnavailableItems(items.stream().anyMatch(item -> !item.available()))
                .build();
    }

    /**
     * The cart of a shopper who has added nothing.
     *
     * @param userId whose cart this is
     * @return an empty cart view
     */
    private static CartView emptyCart(UUID userId) {
        return CartView.builder()
                .userId(userId.toString())
                .items(List.of())
                .itemCount(0)
                .totalQuantity(0)
                .itemsTotal(BigDecimal.ZERO)
                .currency(Product.CURRENCY)
                .hasUnavailableItems(false)
                .build();
    }

    /**
     * Picks the image a cart row should show.
     *
     * @param product the catalogue entry, or {@code null}
     * @return the first gallery image URL, or {@code null}
     */
    private static String firstImage(ProductView product) {
        if (product == null || product.media() == null || product.media().isEmpty()) {
            return null;
        }
        return product.media().stream()
                .filter(m -> "IMAGE".equals(m.type()))
                .map(ProductMediaView::url)
                .findFirst()
                .orElse(null);
    }
}
