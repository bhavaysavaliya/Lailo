package com.lailo.application.features.product.repository;

import com.lailo.application.features.product.models.Product;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;

/** R2DBC repository for products. */
@Repository
public interface ProductRepository extends ReactiveCrudRepository<Product, Integer> {
}
