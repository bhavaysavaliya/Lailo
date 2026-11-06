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

@RestController
@RequestMapping("/product")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    @GetMapping
    public Flux<ProductDto> getProducts() {
        return productService.getProducts();
    }

    @PostMapping
    public Mono<ProductDto> saveProduct(ProductDto productDto) {
        return productService.saveProduct(productDto);
    }
}
