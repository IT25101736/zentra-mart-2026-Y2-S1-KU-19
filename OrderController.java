package com.zentramart.backend;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/orders")
@CrossOrigin(origins = "*")
public class OrderController {
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderTrackingRepository trackingRepository;

    public OrderController(OrderRepository orderRepository,
                           OrderItemRepository orderItemRepository,
                           OrderTrackingRepository trackingRepository) {
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.trackingRepository = trackingRepository;
    }

    @GetMapping("/buyer/{buyerId}")
    public List<OrderDetailsResponse> getBuyerOrders(@PathVariable Long buyerId) {
        return orderRepository.findByBuyerIdOrderByCreatedAtDesc(buyerId).stream()
                .map(this::detailsFor)
                .toList();
    }

    @GetMapping("/seller/{sellerId}")
    public List<OrderDetailsResponse> getSellerOrders(@PathVariable Long sellerId) {
        List<Long> orderIds = orderItemRepository.findDistinctOrderIdsBySellerId(sellerId);
        if (orderIds.isEmpty()) return List.of();
        return orderRepository.findByOrderIdInOrderByCreatedAtDesc(orderIds).stream()
                .map(this::detailsFor)
                .toList();
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<?> getOrder(@PathVariable Long orderId,
                                      @RequestParam(required = false) Long buyerId,
                                      @RequestParam(required = false) Long sellerId) {
        Order order = orderRepository.findById(orderId).orElse(null);
        if (order == null) return ResponseEntity.notFound().build();

        boolean isBuyer = buyerId != null && buyerId.equals(order.getBuyerId());
        boolean isSeller = sellerId != null && orderItemRepository.existsByOrderIdAndSellerId(orderId, sellerId);
        if (!isBuyer && !isSeller) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body("Only the buyer or the seller for this order can view it.");
        }
        return ResponseEntity.ok(detailsFor(order));
    }

    @PutMapping("/{orderId}/status")
    public ResponseEntity<?> updateOrderStatus(@PathVariable Long orderId,
                                                @RequestParam Long sellerId,
                                                @Valid @RequestBody OrderStatusUpdateRequest request) {
        Order order = orderRepository.findById(orderId).orElse(null);
        if (order == null) return ResponseEntity.notFound().build();
        if (!orderItemRepository.existsByOrderIdAndSellerId(orderId, sellerId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body("You can only update orders containing your own listings.");
        }
        if (!OrderStatusTransitions.isAllowed(order.getStatus(), request.getStatus())) {
            return ResponseEntity.badRequest().body(
                    "Invalid status transition. Orders must progress from PENDING to SHIPPED to DELIVERED.");
        }
        if (request.getStatus() == Order.OrderStatus.SHIPPED
                && (request.getTrackingNumber() == null || request.getTrackingNumber().isBlank())
                && (order.getTrackingNumber() == null || order.getTrackingNumber().isBlank())) {
            return ResponseEntity.badRequest().body("A tracking number is required before an order can be shipped.");
        }

        order.setStatus(request.getStatus());
        if (request.getTrackingNumber() != null && !request.getTrackingNumber().isBlank()) {
            order.setTrackingNumber(request.getTrackingNumber().trim());
        }
        if (request.getTrackingUrl() != null && !request.getTrackingUrl().isBlank()) {
            order.setTrackingUrl(request.getTrackingUrl().trim());
        }
        if (request.getStatus() == Order.OrderStatus.SHIPPED && order.getShippedAt() == null) {
            order.setShippedAt(LocalDateTime.now());
        }
        if (request.getStatus() == Order.OrderStatus.DELIVERED && order.getDeliveredAt() == null) {
            order.setDeliveredAt(LocalDateTime.now());
        }
        Order savedOrder = orderRepository.save(order);

        OrderTracking event = new OrderTracking();
        event.setOrderId(orderId);
        event.setStatus(request.getStatus());
        event.setLocation(request.getLocation());
        event.setNote(request.getNote());
        trackingRepository.save(event);

        return ResponseEntity.ok(detailsFor(savedOrder));
    }

    private OrderDetailsResponse detailsFor(Order order) {
        return new OrderDetailsResponse(
                order,
                orderItemRepository.findByOrderId(order.getOrderId()),
                trackingRepository.findByOrderIdOrderByEventTimeAsc(order.getOrderId())
        );
    }
}
