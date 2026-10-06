package com.zentramart.backend.order.controller;

import com.zentramart.backend.admin.repository.DeliveryPartnerRepository;
import com.zentramart.backend.order.model.Delivery;
import com.zentramart.backend.order.model.OrderItem;
import com.zentramart.backend.order.repository.DeliveryRepository;
import com.zentramart.backend.order.repository.OrderItemRepository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

// Read-only delivery details for buyers (Track Order) and sellers (Orders panel).
// Admin-side assigning/updating stays in AdminController under /api/admin.
@RestController
@RequestMapping("/api/deliveries")
@CrossOrigin(origins = "*")
public class DeliveryInfoController {

    private final DeliveryRepository deliveryRepository;
    private final DeliveryPartnerRepository deliveryPartnerRepository;
    private final OrderItemRepository orderItemRepository;

    @Autowired
    public DeliveryInfoController(DeliveryRepository deliveryRepository,
                                  DeliveryPartnerRepository deliveryPartnerRepository,
                                  OrderItemRepository orderItemRepository) {
        this.deliveryRepository = deliveryRepository;
        this.deliveryPartnerRepository = deliveryPartnerRepository;
        this.orderItemRepository = orderItemRepository;
    }

    public static class DeliveryInfo {
        public Long orderId;
        public String status;          // UNASSIGNED, ASSIGNED, PICKED_UP, DELIVERED
        public String courierName;
        public String courierPhone;
        public String vehicleType;
        public LocalDateTime pickupTime;
        public LocalDateTime deliveryTime;
    }

    private DeliveryInfo toInfo(Delivery d) {
        DeliveryInfo v = new DeliveryInfo();
        v.orderId = d.getOrderId();
        v.status = d.getStatus().name();
        v.pickupTime = d.getPickupTime();
        v.deliveryTime = d.getDeliveryTime();
        if (d.getPartnerId() != null) {
            deliveryPartnerRepository.findById(d.getPartnerId()).ifPresent(p -> {
                v.courierName = p.getName();
                v.courierPhone = p.getContactNo();
                v.vehicleType = p.getVehicleType();
            });
        }
        return v;
    }

    // GET /api/deliveries/order/12 - the courier for one order (404 if none arranged yet)
    @GetMapping("/order/{orderId}")
    public ResponseEntity<?> forOrder(@PathVariable Long orderId) {
        return deliveryRepository.findByOrderId(orderId)
                .<ResponseEntity<?>>map(d -> ResponseEntity.ok(toInfo(d)))
                .orElse(ResponseEntity.notFound().build());
    }

    // GET /api/deliveries/seller/3 - couriers for every order that contains this seller's items
    @GetMapping("/seller/{sellerId}")
    public List<DeliveryInfo> forSeller(@PathVariable Long sellerId) {
        List<Long> orderIds = orderItemRepository.findAll().stream()
                .filter(oi -> Objects.equals(oi.getSellerId(), sellerId))
                .map(OrderItem::getOrderId).distinct()
                .collect(Collectors.toList());
        return deliveryRepository.findAll().stream()
                .filter(d -> orderIds.contains(d.getOrderId()))
                .map(this::toInfo)
                .collect(Collectors.toList());
    }
}