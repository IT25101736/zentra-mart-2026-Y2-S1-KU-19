package com.zentramart.backend.cart.repository;

import com.zentramart.backend.cart.model.DiscountCode;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface DiscountCodeRepository extends JpaRepository<DiscountCode, Long> {
    boolean existsByCodeNameIgnoreCase(String codeName);
    Optional<DiscountCode> findByCodeNameIgnoreCase(String codeName);
}