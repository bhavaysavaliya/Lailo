package com.lailo.application.features.product.controller;

import com.lailo.application.features.product.dto.ProductDto;
import com.lailo.application.features.product.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** HTTP endpoints for products (sample feature). */
@RestController
@RequestMapping("/product")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    /**
     * Lists all products.
     *
     * @return every product as a DTO
     */
    @GetMapping
    public Flux<ProductDto> getProducts() {
        return productService.getProducts();
    }

    /**
     * Creates a product. The parameter has no {@code @RequestBody}, so WebFlux binds it from query or form
     * parameters, not from a JSON body.
     *
     * @param productDto name and price
     * @return the saved product
     */
    @PostMapping
    public Mono<ProductDto> saveProduct(ProductDto productDto) {
        return productService.saveProduct(productDto);
    }
}
