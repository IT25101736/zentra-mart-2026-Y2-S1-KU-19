package com.zentramart.backend;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {
    List<OrderItem> findByOrderId(Long orderId);
    boolean existsByOrderIdAndSellerId(Long orderId, Long sellerId);

    @Query("select distinct item.orderId from OrderItem item where item.sellerId = :sellerId")
    List<Long> findDistinctOrderIdsBySellerId(@Param("sellerId") Long sellerId);
}
