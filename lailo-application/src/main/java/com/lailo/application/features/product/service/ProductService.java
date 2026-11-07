package com.lailo.application.features.product.service;

import com.lailo.application.features.product.dto.ProductDto;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Product use cases. */
public interface ProductService {
    /**
     * Lists all products.
     *
     * @return every product as a DTO
     */
    public Flux<ProductDto> getProducts();

    /**
     * Saves a new product.
     *
     * @param productDto product data
     * @return the saved product
     */
    public Mono<ProductDto> saveProduct(ProductDto productDto);
}
