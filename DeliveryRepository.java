package com.zentramart.backend.order.repository;

import com.zentramart.backend.order.model.Delivery;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface DeliveryRepository extends JpaRepository<Delivery, Long> {
    Optional<Delivery> findByOrderId(Long orderId);
}