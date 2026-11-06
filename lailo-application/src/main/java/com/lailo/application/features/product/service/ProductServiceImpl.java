package com.lailo.application.features.product.service;

import com.lailo.application.features.product.dto.ProductDto;
import com.lailo.application.features.product.mappers.ProductMapper;
import com.lailo.application.features.product.models.Product;
import com.lailo.application.features.product.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class ProductServiceImpl implements ProductService {

    private final ProductRepository productRepository;

    @Override
    public Flux<ProductDto> getProducts() {
        return productRepository.findAll().map(ProductMapper::toProductDto);
    }

    @Override
    public Mono<ProductDto> saveProduct(ProductDto productDto) {
        Product product = ProductMapper.toProduct(productDto);
        return productRepository.save(product).map(ProductMapper::toProductDto);
    }
}
