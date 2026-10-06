package com.zentramart.backend.catalog.repository;

import com.zentramart.backend.catalog.model.Favorite;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface FavoriteRepository extends JpaRepository<Favorite, Long> {
    List<Favorite> findByBuyerIdOrderByCreatedAtDesc(Long buyerId);
    Optional<Favorite> findByBuyerIdAndProductId(Long buyerId, Long productId);
}