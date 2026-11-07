package com.lailo.application.features.product.mappers;

import com.lailo.application.features.product.dto.ProductDto;
import com.lailo.application.features.product.models.Product;

/** Converts between the product entity and its DTO. */
public class ProductMapper {
    /**
     * Maps an entity to a DTO.
     *
     * @param product the entity
     * @return a DTO with name and price
     */
    public static ProductDto toProductDto(Product product) {
        ProductDto productDto = new ProductDto();
        productDto.setName(product.getName());
        productDto.setPrice(product.getPrice());
        return productDto;
    }

    /**
     * Maps a DTO to a new, unsaved entity.
     *
     * @param productDto the DTO
     * @return an entity without an id
     */
    public static Product toProduct(ProductDto productDto) {
        Product product = new Product();
        product.setName(productDto.getName());
        product.setPrice(productDto.getPrice());
        return product;
    }
}
