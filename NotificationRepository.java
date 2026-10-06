package com.zentramart.backend.order.repository;

import com.zentramart.backend.order.model.Notification;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Long> {
    List<Notification> findByUserIdOrderBySentDateDesc(Long userId);
}