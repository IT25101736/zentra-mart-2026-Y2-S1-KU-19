package com.zentramart.backend.cart.repository;

import com.zentramart.backend.cart.model.CartItem;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface CartItemRepository extends JpaRepository<CartItem, Long> {
    List<CartItem> findByBuyerId(Long buyerId);
    Optional<CartItem> findByBuyerIdAndProductId(Long buyerId, Long productId);
    void deleteByBuyerId(Long buyerId);
}
