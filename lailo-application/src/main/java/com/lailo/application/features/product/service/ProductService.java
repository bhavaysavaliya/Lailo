package com.lailo.application.features.product.service;

import com.lailo.application.features.product.dto.ProductDto;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface ProductService {
    public Flux<ProductDto> getProducts();

    public Mono<ProductDto> saveProduct(ProductDto productDto);
}
