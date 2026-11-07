package com.lailo.application.features.product.models;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/** Product entity (table {@code lailo_products}). */
@Table("lailo_products")
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
@ToString
public class Product {
    @Id
    private Integer id;
    private String name;
    private Double price;
}
