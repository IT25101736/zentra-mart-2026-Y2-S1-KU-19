package com.zentramart.backend;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

// You don't write the SQL yourself here - Spring Data JPA generates it automatically
// based on the method names below. Extending JpaRepository already gives you
// findAll(), findById(), save(), deleteById(), etc. for free.
public interface ProductRepository extends JpaRepository<Product, Long> {

    // PBI-09: keyword search - Spring builds:
    // SELECT * FROM products WHERE title LIKE %keyword%
    List<Product> findByTitleContainingIgnoreCase(String keyword);

    // PBI-10: filter by category
    List<Product> findByCategoryId(Integer categoryId);

    // Only show listings buyers should see
    List<Product> findByStatus(Product.ProductStatus status);

    // Seller dashboard: get everything a specific seller owns, any status
    List<Product> findBySellerId(Long sellerId);
}