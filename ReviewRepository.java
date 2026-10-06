package com.zentramart.backend.catalog.repository;

import com.zentramart.backend.catalog.model.Review;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ReviewRepository extends JpaRepository<Review, Long> {

    // All reviews for one product (product detail page)
    List<Review> findByProductId(Long productId);

    // Used to check/prevent a buyer reviewing the same product twice
    Optional<Review> findByBuyerIdAndProductId(Long buyerId, Long productId);
}
