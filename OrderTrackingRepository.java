package com.zentramart.backend;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OrderTrackingRepository extends JpaRepository<OrderTracking, Long> {
    List<OrderTracking> findByOrderIdOrderByEventTimeAsc(Long orderId);
}
