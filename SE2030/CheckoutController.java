package com.zentramart.backend.cart.controller;

import com.zentramart.backend.account.repository.UserRepository;
import com.zentramart.backend.cart.model.CartItem;
import com.zentramart.backend.cart.model.DiscountCode;
import com.zentramart.backend.cart.repository.CartItemRepository;
import com.zentramart.backend.cart.repository.DiscountCodeRepository;
import com.zentramart.backend.listing.model.Product;
import com.zentramart.backend.listing.repository.ProductRepository;
import com.zentramart.backend.order.model.Notification;
import com.zentramart.backend.order.model.Order;
import com.zentramart.backend.order.model.OrderItem;
import com.zentramart.backend.order.repository.NotificationRepository;
import com.zentramart.backend.order.repository.OrderItemRepository;
import com.zentramart.backend.order.repository.OrderRepository;
import com.zentramart.backend.order.service.OrderEmailService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class CheckoutController {

    private final CartItemRepository cartItemRepository;
    private final ProductRepository productRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final UserRepository userRepository;
    private final DiscountCodeRepository discountCodeRepository;
    private final NotificationRepository notificationRepository;
    private final OrderEmailService orderEmailService;

    @Autowired
    public CheckoutController(CartItemRepository cartItemRepository, ProductRepository productRepository,
                              OrderRepository orderRepository, OrderItemRepository orderItemRepository,
                              UserRepository userRepository, DiscountCodeRepository discountCodeRepository,
                              NotificationRepository notificationRepository,
                              OrderEmailService orderEmailService) {
        this.cartItemRepository = cartItemRepository;
        this.productRepository = productRepository;
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.userRepository = userRepository;
        this.discountCodeRepository = discountCodeRepository;
        this.notificationRepository = notificationRepository;
        this.orderEmailService = orderEmailService;
    }

    // ---------- helpers ----------

    private DiscountCode findUsableCode(String code) {
        if (code == null || code.trim().isEmpty()) return null;
        DiscountCode dc = discountCodeRepository.findByCodeNameIgnoreCase(code.trim()).orElse(null);
        if (dc == null) return null;
        if (dc.getIsActive() == null || !dc.getIsActive()) return null;
        if (dc.getExpiryDate() != null && dc.getExpiryDate().isBefore(LocalDate.now())) return null;
        return dc;
    }

    private void notify(Long userId, String type, String message) {
        if (userId == null) return;
        Notification n = new Notification();
        n.setUserId(userId);
        n.setType(type);
        n.setMessage(message.length() > 500 ? message.substring(0, 500) : message);
        notificationRepository.save(n);
    }

    private Set<Long> sellerIdsForOrder(Long orderId) {
        return orderItemRepository.findByOrderId(orderId).stream()
                .map(OrderItem::getSellerId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    // ---------- discount codes ----------

    @GetMapping("/discount-codes/validate")
    public ResponseEntity<?> validateCode(@RequestParam String code) {
        DiscountCode dc = findUsableCode(code);
        if (dc == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("This promo code is invalid or has expired.");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("codeName", dc.getCodeName());
        body.put("discountPercentage", dc.getDiscountPercentage());
        body.put("expiryDate", dc.getExpiryDate());
        return ResponseEntity.ok(body);
    }

    // ---------- checkout ----------

    public static class CheckoutRequest {
        public Long buyerId;
        public String shippingAddress;
        public String discountCode; // optional
    }

    @Transactional
    @PostMapping("/checkout")
    public ResponseEntity<?> checkout(@RequestBody CheckoutRequest req) {
        if (req.shippingAddress == null || req.shippingAddress.trim().isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Shipping address is required.");
        }

        List<CartItem> cartItems = cartItemRepository.findByBuyerId(req.buyerId);
        if (cartItems.isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Your cart is empty.");
        }

        DiscountCode dc = null;
        if (req.discountCode != null && !req.discountCode.trim().isEmpty()) {
            dc = findUsableCode(req.discountCode);
            if (dc == null) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("This promo code is invalid or has expired.");
            }
        }

        for (CartItem item : cartItems) {
            Product product = productRepository.findById(item.getProductId()).orElse(null);
            if (product == null || product.getStatus() != Product.ProductStatus.ACTIVE) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body("An item in your cart is no longer available.");
            }
            if (item.getQuantity() > product.getStockQty()) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body("Not enough stock for \"" + product.getTitle() + "\".");
            }
        }

        Order order = new Order();
        order.setBuyerId(req.buyerId);
        order.setShippingAddress(req.shippingAddress.trim());
        order.setPaymentStatus(Order.PaymentStatus.PAID);
        order.setOrderStatus(Order.OrderStatus.PENDING);
        order.setTotalAmount(BigDecimal.ZERO);
        order.setDiscountAmount(BigDecimal.ZERO);
        order = orderRepository.save(order);

        BigDecimal subtotal = BigDecimal.ZERO;
        Set<Long> sellerIds = new LinkedHashSet<>();

        for (CartItem item : cartItems) {
            Product product = productRepository.findById(item.getProductId()).orElse(null);

            OrderItem oi = new OrderItem();
            oi.setOrderId(order.getOrderId());
            oi.setProductId(product.getProductId());
            oi.setSellerId(product.getSellerId());
            oi.setQuantity(item.getQuantity());
            oi.setPriceAtPurchase(product.getPrice());
            orderItemRepository.save(oi);
            sellerIds.add(product.getSellerId());

            subtotal = subtotal.add(product.getPrice().multiply(BigDecimal.valueOf(item.getQuantity())));

            product.setStockQty(product.getStockQty() - item.getQuantity());
            if (product.getStockQty() <= 0) {
                product.setStatus(Product.ProductStatus.SOLD);
            }
            productRepository.save(product);
        }

        BigDecimal discount = BigDecimal.ZERO;
        if (dc != null) {
            discount = subtotal.multiply(dc.getDiscountPercentage())
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            order.setDiscountCode(dc.getCodeName());
        }
        order.setDiscountAmount(discount);
        order.setTotalAmount(subtotal.subtract(discount));
        orderRepository.save(order);
        cartItemRepository.deleteByBuyerId(req.buyerId);

        notify(req.buyerId, "ORDER", "Your order #" + order.getOrderId() + " was placed successfully.");
        for (Long sellerId : sellerIds) {
            notify(sellerId, "ORDER", "New order #" + order.getOrderId() + " - check your Orders panel.");
        }

        // Receipt to the buyer + "new order" email to each seller (sent after the order is saved)
        orderEmailService.orderPlaced(order.getOrderId());

        return ResponseEntity.ok(order);
    }

    @GetMapping("/orders/{buyerId}")
    public List<Order> getOrders(@PathVariable Long buyerId) {
        List<Order> orders = orderRepository.findByBuyerId(buyerId);
        orders.sort((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()));
        return orders;
    }

    public static class OrderItemView {
        public Long orderItemId;
        public Long productId;
        public Long sellerId;
        public String title;
        public String imageUrl;
        public Integer quantity;
        public BigDecimal priceAtPurchase;
        public BigDecimal lineTotal;
    }

    @GetMapping("/orders/{orderId}/items")
    public List<OrderItemView> getOrderItems(@PathVariable Long orderId) {
        return orderItemRepository.findByOrderId(orderId).stream().map(oi -> {
            OrderItemView v = new OrderItemView();
            v.orderItemId = oi.getOrderItemId();
            v.productId = oi.getProductId();
            v.sellerId = oi.getSellerId();
            v.quantity = oi.getQuantity();
            v.priceAtPurchase = oi.getPriceAtPurchase();
            v.lineTotal = oi.getPriceAtPurchase().multiply(BigDecimal.valueOf(oi.getQuantity()));
            Product product = productRepository.findById(oi.getProductId()).orElse(null);
            if (product != null) {
                v.title = product.getTitle();
                v.imageUrl = product.getImageUrl();
            } else {
                v.title = "Product no longer available";
            }
            return v;
        }).collect(Collectors.toList());
    }

    @PutMapping("/orders/{orderId}/cancel")
    public ResponseEntity<?> cancelOrder(@PathVariable Long orderId) {
        Order order = orderRepository.findById(orderId).orElse(null);
        if (order == null) {
            return ResponseEntity.notFound().build();
        }
        if (order.getOrderStatus() != Order.OrderStatus.PENDING) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("This order has already shipped and can no longer be cancelled.");
        }
        order.setOrderStatus(Order.OrderStatus.CANCELLED);
        Order saved = orderRepository.save(order);
        for (Long sellerId : sellerIdsForOrder(orderId)) {
            notify(sellerId, "ORDER", "Order #" + orderId + " was cancelled by the buyer.");
        }
        orderEmailService.orderStatusChanged(orderId, Order.OrderStatus.CANCELLED);
        return ResponseEntity.ok(saved);
    }

    public static class UpdateOrderStatusRequest {
        public String status; // PENDING, SHIPPED, or DELIVERED
    }

    @PutMapping("/orders/{orderId}/status")
    public ResponseEntity<?> updateOrderStatus(@PathVariable Long orderId, @RequestBody UpdateOrderStatusRequest req) {
        Order order = orderRepository.findById(orderId).orElse(null);
        if (order == null) {
            return ResponseEntity.notFound().build();
        }
        if (order.getOrderStatus() == Order.OrderStatus.CANCELLED) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("This order was cancelled and can't be updated.");
        }
        Order.OrderStatus newStatus;
        try {
            newStatus = Order.OrderStatus.valueOf(req.status);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Invalid status.");
        }
        if (newStatus == Order.OrderStatus.CANCELLED) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Sellers can't cancel orders.");
        }
        boolean changed = order.getOrderStatus() != newStatus;
        order.setOrderStatus(newStatus);
        Order saved = orderRepository.save(order);

        if (changed) {
            String label = newStatus == Order.OrderStatus.PENDING ? "Processing"
                    : newStatus == Order.OrderStatus.SHIPPED ? "Shipped" : "Delivered";
            notify(order.getBuyerId(), "ORDER_STATUS", "Your order #" + orderId + " is now " + label + ".");
            orderEmailService.orderStatusChanged(orderId, newStatus); // shipped / delivered email
        }
        return ResponseEntity.ok(saved);
    }

    public static class SellerOrderItemView {
        public Long orderId;
        public LocalDateTime orderDate;
        public String orderStatus;
        public Long buyerId;
        public String buyerName;
        public String buyerEmail;
        public Long productId;
        public String productTitle;
        public Integer quantity;
        public BigDecimal pricePaid;
        public BigDecimal lineTotal;
    }

    @GetMapping("/orders/seller/{sellerId}/items")
    public List<SellerOrderItemView> getSellerOrderItems(@PathVariable Long sellerId) {
        return orderItemRepository.findAll().stream()
                .filter(oi -> oi.getSellerId().equals(sellerId))
                .map(oi -> {
                    SellerOrderItemView v = new SellerOrderItemView();
                    v.orderId = oi.getOrderId();
                    v.productId = oi.getProductId();
                    v.quantity = oi.getQuantity();
                    v.pricePaid = oi.getPriceAtPurchase();
                    v.lineTotal = oi.getPriceAtPurchase().multiply(BigDecimal.valueOf(oi.getQuantity()));

                    orderRepository.findById(oi.getOrderId()).ifPresent(order -> {
                        v.orderDate = order.getCreatedAt();
                        v.orderStatus = order.getOrderStatus().name();
                        v.buyerId = order.getBuyerId();
                        userRepository.findById(order.getBuyerId()).ifPresent(buyer -> {
                            v.buyerName = buyer.getFullName();
                            v.buyerEmail = buyer.getEmail();
                        });
                    });

                    productRepository.findById(oi.getProductId()).ifPresent(p -> v.productTitle = p.getTitle());

                    return v;
                })
                .sorted((a, b) -> {
                    if (a.orderDate == null || b.orderDate == null) return 0;
                    return b.orderDate.compareTo(a.orderDate);
                })
                .collect(Collectors.toList());
    }
}