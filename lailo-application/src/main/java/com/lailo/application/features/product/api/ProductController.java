package com.lailo.application.features.product.api;

import com.lailo.application.common.cqrs.CommandBus;
import com.lailo.application.common.cqrs.QueryBus;
import com.lailo.application.features.product.api.dto.ProductRequest;
import com.lailo.application.features.product.application.command.ArchiveProductCommand;
import com.lailo.application.features.product.application.command.CreateProductCommand;
import com.lailo.application.features.product.application.command.UpdateProductCommand;
import com.lailo.application.features.product.application.query.GetProductQuery;
import com.lailo.application.features.product.application.query.ProductPage;
import com.lailo.application.features.product.application.query.ProductSort;
import com.lailo.application.features.product.application.query.ProductView;
import com.lailo.application.features.product.application.query.SearchProductsQuery;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;

/**
 * Thin adapter: HTTP to commands and queries. No business logic lives here.
 *
 * <p>Reads are public (the catalogue is the shop window). Writes only require a signed-in caller today;
 * once seller / catalogue-admin roles exist they belong behind {@code hasAuthority}.</p>
 */
@RestController
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
public class ProductController {

    private static final Duration BROWSE_CACHE = Duration.ofSeconds(60);

    private final CommandBus commandBus;
    private final QueryBus queryBus;

    /**
     * Browses or searches the catalogue. Paging is clamped by the query itself.
     *
     * @param q free text, or {@code null} to browse everything
     * @param category exact category filter, or {@code null}
     * @param minPrice lowest acceptable price, or {@code null}
     * @param maxPrice highest acceptable price, or {@code null}
     * @param inStock when true, hide sold-out products
     * @param sort ordering, {@code NEWEST} by default
     * @param page zero-based page index
     * @param size page size, at most 100
     * @return the matching page of products
     */
    @GetMapping
    public Mono<ResponseEntity<ProductPage>> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice,
            @RequestParam(defaultValue = "false") boolean inStock,
            @RequestParam(defaultValue = "NEWEST") ProductSort sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return queryBus.ask(SearchProductsQuery.builder()
                        .text(q)
                        .category(category)
                        .minPrice(minPrice)
                        .maxPrice(maxPrice)
                        .inStockOnly(inStock)
                        .sort(sort)
                        .page(page)
                        .size(size)
                        .build())
                .map(result -> ResponseEntity.ok()
                        .cacheControl(CacheControl.maxAge(BROWSE_CACHE).cachePublic())
                        .body(result));
    }

    /**
     * Reads one product.
     *
     * @param productId the product id
     * @return the product, or 404 {@code PRODUCT_NOT_FOUND}
     */
    @GetMapping("/{productId}")
    public Mono<ResponseEntity<ProductView>> get(@PathVariable UUID productId) {
        return queryBus.ask(new GetProductQuery(productId))
                .map(view -> ResponseEntity.ok()
                        .cacheControl(CacheControl.maxAge(BROWSE_CACHE).cachePublic())
                        .body(view));
    }

    /**
     * Adds a product to the catalogue.
     *
     * @param req the product details
     * @return 201 with the created product and its {@code Location}
     */
    @PostMapping
    public Mono<ResponseEntity<ProductView>> create(@Valid @RequestBody ProductRequest req) {
        return commandBus.dispatch(new CreateProductCommand(req.toDetails()))
                .map(view -> ResponseEntity.created(URI.create("/api/v1/products/" + view.id())).body(view));
    }

    /**
     * Replaces a product's editable details.
     *
     * @param productId the product to change
     * @param req the new details
     * @return the updated product, or 404 {@code PRODUCT_NOT_FOUND} / 409 {@code PRODUCT_ARCHIVED}
     */
    @PutMapping("/{productId}")
    public Mono<ProductView> update(@PathVariable UUID productId, @Valid @RequestBody ProductRequest req) {
        return commandBus.dispatch(new UpdateProductCommand(productId, req.toDetails()));
    }

    /**
     * Withdraws a product from the catalogue. Idempotent.
     *
     * @param productId the product to withdraw
     * @return 204 No Content, also when it was already archived
     */
    @DeleteMapping("/{productId}")
    public Mono<ResponseEntity<Void>> archive(@PathVariable UUID productId) {
        return commandBus.dispatch(new ArchiveProductCommand(productId))
                .thenReturn(ResponseEntity.noContent().build());
    }
}
