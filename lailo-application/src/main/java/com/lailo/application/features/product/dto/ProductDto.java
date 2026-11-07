package com.lailo.application.features.product.dto;

import lombok.*;

/** Product data exposed over HTTP. */
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
@ToString
public class ProductDto {
    private String name;
    private Double price;
}
