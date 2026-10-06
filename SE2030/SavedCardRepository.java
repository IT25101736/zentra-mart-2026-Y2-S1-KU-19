package com.zentramart.backend.cart.repository;

import com.zentramart.backend.cart.model.SavedCard;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface SavedCardRepository extends JpaRepository<SavedCard, Long> {
    List<SavedCard> findByUserIdOrderByCreatedAtDesc(Long userId);
}