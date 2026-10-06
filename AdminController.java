package com.zentramart.backend;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin")
@CrossOrigin(origins = "*")
public class AdminController {

    private final UserRepository userRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final ProductRepository productRepository;
    private final ReviewRepository reviewRepository;
    private final CategoryRepository categoryRepository;
    private final DiscountCodeRepository discountCodeRepository;
    private final DeliveryPartnerRepository deliveryPartnerRepository;
    private final DeliveryRepository deliveryRepository;
    private final ComplaintRepository complaintRepository;
    private final NotificationRepository notificationRepository;

    // Sends the "shipped" / "delivered" emails to the buyer
    @Autowired
    private OrderEmailService orderEmailService;

    @Autowired
    public AdminController(UserRepository userRepository, OrderRepository orderRepository,
                           OrderItemRepository orderItemRepository, ProductRepository productRepository,
                           ReviewRepository reviewRepository, CategoryRepository categoryRepository,
                           DiscountCodeRepository discountCodeRepository,
                           DeliveryPartnerRepository deliveryPartnerRepository,
                           DeliveryRepository deliveryRepository, ComplaintRepository complaintRepository,
                           NotificationRepository notificationRepository) {
        this.userRepository = userRepository;
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.productRepository = productRepository;
        this.reviewRepository = reviewRepository;
        this.categoryRepository = categoryRepository;
        this.discountCodeRepository = discountCodeRepository;
        this.deliveryPartnerRepository = deliveryPartnerRepository;
        this.deliveryRepository = deliveryRepository;
        this.complaintRepository = complaintRepository;
        this.notificationRepository = notificationRepository;
    }

    private String userName(Long userId) {
        if (userId == null) return null;
        return userRepository.findById(userId).map(User::getFullName).orElse("Deleted user");
    }

    private static boolean blank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static ResponseEntity<?> bad(String message) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(message);
    }

    private void notifyUser(Long userId, String type, String message) {
        if (userId == null) return;
        Notification n = new Notification();
        n.setUserId(userId);
        n.setType(type);
        n.setMessage(message.length() > 500 ? message.substring(0, 500) : message);
        notificationRepository.save(n);
    }

    // Tells the buyer and every seller in the order what just happened to the delivery
    private void notifyDeliveryChange(Delivery d, boolean partnerChanged, boolean statusChanged) {
        if (!partnerChanged && !statusChanged) return;
        Order order = orderRepository.findById(d.getOrderId()).orElse(null);
        if (order == null) return;

        DeliveryPartner partner = d.getPartnerId() == null ? null
                : deliveryPartnerRepository.findById(d.getPartnerId()).orElse(null);
        String courier = partner == null ? "a courier"
                : partner.getName() + " (" + partner.getContactNo()
                  + (blank(partner.getVehicleType()) ? "" : ", " + partner.getVehicleType()) + ")";
        String orderRef = "order #" + order.getOrderId();

        String buyerMsg = null;
        String sellerMsg = null;
        switch (d.getStatus()) {
            case ASSIGNED:
                buyerMsg = "Courier " + courier + " has been assigned to your " + orderRef + ".";
                sellerMsg = "Courier " + courier + " will collect " + orderRef + ". Please have it ready.";
                break;
            case PICKED_UP:
                buyerMsg = "Your " + orderRef + " was picked up by " + courier + " and is on the way.";
                sellerMsg = "Courier " + courier + " picked up " + orderRef + ".";
                break;
            case DELIVERED:
                buyerMsg = "Your " + orderRef + " was delivered by " + courier + ". Enjoy!";
                sellerMsg = orderRef.substring(0, 1).toUpperCase() + orderRef.substring(1) + " was delivered to the buyer.";
                break;
            default: // UNASSIGNED
                if (partnerChanged) buyerMsg = "The courier for your " + orderRef + " is being changed. We'll let you know who's next.";
        }

        if (buyerMsg != null) notifyUser(order.getBuyerId(), "DELIVERY", buyerMsg);
        if (sellerMsg != null) {
            List<Long> sellerIds = orderItemRepository.findByOrderId(order.getOrderId()).stream()
                    .map(OrderItem::getSellerId).distinct().collect(Collectors.toList());
            for (Long sellerId : sellerIds) notifyUser(sellerId, "DELIVERY", sellerMsg);
        }
    }

    // ============================== USERS ==============================

    // GET /api/admin/users
    @GetMapping("/users")
    public List<User> getAllUsers() {
        List<User> users = userRepository.findAll();
        users.forEach(u -> u.setPasswordHash(null)); // never expose password hashes
        return users;
    }

    // PUT /api/admin/users/4/suspend
    @PutMapping("/users/{userId}/suspend")
    public ResponseEntity<?> suspendUser(@PathVariable Long userId) {
        return updateStatus(userId, User.Status.SUSPENDED);
    }

    // PUT /api/admin/users/4/activate
    @PutMapping("/users/{userId}/activate")
    public ResponseEntity<?> activateUser(@PathVariable Long userId) {
        return updateStatus(userId, User.Status.ACTIVE);
    }

    // PUT /api/admin/users/4/deactivate  (soft delete - keeps order history intact)
    @PutMapping("/users/{userId}/deactivate")
    public ResponseEntity<?> deactivateUser(@PathVariable Long userId) {
        return updateStatus(userId, User.Status.DEACTIVATED);
    }

    private ResponseEntity<?> updateStatus(Long userId, User.Status newStatus) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) return ResponseEntity.notFound().build();
        if (user.getRole() == User.Role.ADMIN) return bad("The admin account can't be changed here.");
        user.setStatus(newStatus);
        User saved = userRepository.save(user);
        saved.setPasswordHash(null);
        return ResponseEntity.ok(saved);
    }

    public static class UpdateUserRequest {
        public String fullName;
        public String email;
        public String phone;
        public String address;
        public String city;
        public String country;
    }

    // PUT /api/admin/users/4  - edit a buyer's or seller's details (role can't be changed)
    @PutMapping("/users/{userId}")
    public ResponseEntity<?> updateUser(@PathVariable Long userId, @RequestBody UpdateUserRequest req) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) return ResponseEntity.notFound().build();
        if (blank(req.fullName) || blank(req.email)) return bad("Name and email are required.");

        String email = req.email.trim();
        boolean emailTaken = userRepository.findAll().stream()
                .anyMatch(u -> !u.getUserId().equals(userId) && u.getEmail().equalsIgnoreCase(email));
        if (emailTaken) return bad("That email is already in use.");

        user.setFullName(req.fullName.trim());
        user.setEmail(email);
        user.setPhone(req.phone);
        user.setAddress(req.address);
        user.setCity(req.city);
        user.setCountry(req.country);
        User saved = userRepository.save(user);
        saved.setPasswordHash(null);
        return ResponseEntity.ok(saved);
    }

    // ============================== ORDERS (read) ==============================

    public static class AdminOrderItemView {
        public Long orderId;
        public LocalDateTime orderDate;
        public String orderStatus;
        public String paymentStatus;
        public Long buyerId;
        public String buyerName;
        public Long sellerId;
        public String sellerName;
        public Long productId;
        public String productTitle;
        public Integer quantity;
        public BigDecimal pricePaid;
        public BigDecimal lineTotal;
    }

    // GET /api/admin/orders  - one row per item: who bought what, from which seller, when, for how much
    @GetMapping("/orders")
    public List<AdminOrderItemView> getAllOrderItems() {
        return orderItemRepository.findAll().stream().map(oi -> {
            AdminOrderItemView v = new AdminOrderItemView();
            v.orderId = oi.getOrderId();
            v.productId = oi.getProductId();
            v.sellerId = oi.getSellerId();
            v.sellerName = userName(oi.getSellerId());
            v.quantity = oi.getQuantity();
            v.pricePaid = oi.getPriceAtPurchase();
            v.lineTotal = oi.getPriceAtPurchase().multiply(BigDecimal.valueOf(oi.getQuantity()));
            orderRepository.findById(oi.getOrderId()).ifPresent(order -> {
                v.orderDate = order.getCreatedAt();
                v.orderStatus = order.getOrderStatus().name();
                v.paymentStatus = order.getPaymentStatus().name();
                v.buyerId = order.getBuyerId();
                v.buyerName = userName(order.getBuyerId());
            });
            productRepository.findById(oi.getProductId()).ifPresent(p -> v.productTitle = p.getTitle());
            return v;
        }).sorted((a, b) -> {
            if (a.orderDate == null || b.orderDate == null) return 0;
            return b.orderDate.compareTo(a.orderDate);
        }).collect(Collectors.toList());
    }

    // ============================== PRODUCTS ==============================

    public static class AdminProductView {
        public Long productId;
        public String title;
        public String description;
        public BigDecimal price;
        public Integer stockQty;
        public Integer categoryId;
        public String status;
        public Long sellerId;
        public String sellerName;
        public LocalDateTime createdAt;
    }

    // GET /api/admin/products
    @GetMapping("/products")
    public List<AdminProductView> getAllProducts() {
        return productRepository.findAll().stream().map(p -> {
            AdminProductView v = new AdminProductView();
            v.productId = p.getProductId();
            v.title = p.getTitle();
            v.description = p.getDescription();
            v.price = p.getPrice();
            v.stockQty = p.getStockQty();
            v.categoryId = p.getCategoryId();
            v.status = p.getStatus().name();
            v.sellerId = p.getSellerId();
            v.sellerName = userName(p.getSellerId());
            v.createdAt = p.getCreatedAt();
            return v;
        }).collect(Collectors.toList());
    }

    public static class UpdateProductRequest {
        public String title;
        public String description;
        public BigDecimal price;
        public Integer stockQty;
        public Integer categoryId;
        public String status; // ACTIVE, SOLD, ARCHIVED, FLAGGED
    }

    // PUT /api/admin/products/5  - edit any listing, including flagging it
    @PutMapping("/products/{productId}")
    public ResponseEntity<?> updateProduct(@PathVariable Long productId, @RequestBody UpdateProductRequest req) {
        Product product = productRepository.findById(productId).orElse(null);
        if (product == null) return ResponseEntity.notFound().build();
        if (blank(req.title)) return bad("Title is required.");
        if (req.price == null || req.price.compareTo(BigDecimal.ZERO) < 0) return bad("Price must be 0 or more.");
        if (req.stockQty == null || req.stockQty < 0) return bad("Stock must be 0 or more.");
        if (req.categoryId == null || !categoryRepository.existsById(req.categoryId)) return bad("Pick a valid category.");

        Product.ProductStatus status;
        try {
            status = Product.ProductStatus.valueOf(req.status);
        } catch (Exception e) {
            return bad("Invalid status.");
        }

        product.setTitle(req.title.trim());
        product.setDescription(req.description);
        product.setPrice(req.price);
        product.setStockQty(req.stockQty);
        product.setCategoryId(req.categoryId);
        product.setStatus(status);
        return ResponseEntity.ok(productRepository.save(product));
    }

    // DELETE /api/admin/products/5
    // Products that appear in past orders can't be hard-deleted (order_items
    // references them), so those are archived instead to keep order history intact.
    @DeleteMapping("/products/{productId}")
    public ResponseEntity<?> deleteProduct(@PathVariable Long productId) {
        Product product = productRepository.findById(productId).orElse(null);
        if (product == null) return ResponseEntity.notFound().build();

        boolean hasOrders = orderItemRepository.findAll().stream()
                .anyMatch(oi -> oi.getProductId().equals(productId));
        if (hasOrders) {
            product.setStatus(Product.ProductStatus.ARCHIVED);
            productRepository.save(product);
            return ResponseEntity.ok("This product has past orders, so it was archived instead of deleted.");
        }
        productRepository.deleteById(productId);
        return ResponseEntity.ok("Product deleted.");
    }

    // ============================== CATEGORIES ==============================

    public static class CategoryRequest {
        public String name;
    }

    // GET /api/admin/categories
    @GetMapping("/categories")
    public List<Category> getCategories() {
        return categoryRepository.findAll();
    }

    // POST /api/admin/categories
    @PostMapping("/categories")
    public ResponseEntity<?> createCategory(@RequestBody CategoryRequest req) {
        if (blank(req.name)) return bad("Category name is required.");
        if (categoryRepository.existsByNameIgnoreCase(req.name.trim())) return bad("That category already exists.");
        Category c = new Category();
        c.setName(req.name.trim());
        return ResponseEntity.ok(categoryRepository.save(c));
    }

    // PUT /api/admin/categories/3
    @PutMapping("/categories/{categoryId}")
    public ResponseEntity<?> renameCategory(@PathVariable Integer categoryId, @RequestBody CategoryRequest req) {
        Category c = categoryRepository.findById(categoryId).orElse(null);
        if (c == null) return ResponseEntity.notFound().build();
        if (blank(req.name)) return bad("Category name is required.");
        String name = req.name.trim();
        if (!name.equalsIgnoreCase(c.getName()) && categoryRepository.existsByNameIgnoreCase(name)) {
            return bad("That category already exists.");
        }
        c.setName(name);
        return ResponseEntity.ok(categoryRepository.save(c));
    }

    // DELETE /api/admin/categories/3  - blocked while any product still uses it
    @DeleteMapping("/categories/{categoryId}")
    public ResponseEntity<?> deleteCategory(@PathVariable Integer categoryId) {
        if (!categoryRepository.existsById(categoryId)) return ResponseEntity.notFound().build();
        int inUse = productRepository.findByCategoryId(categoryId).size();
        if (inUse > 0) {
            return bad(inUse + " product(s) still use this category. Move or delete them first.");
        }
        categoryRepository.deleteById(categoryId);
        return ResponseEntity.ok().build();
    }

    // ============================== REVIEWS ==============================

    public static class AdminReviewView {
        public Long reviewId;
        public Long productId;
        public String productTitle;
        public Long buyerId;
        public String buyerName;
        public Integer rating;
        public String comment;
        public LocalDateTime createdAt;
    }

    // GET /api/admin/reviews
    @GetMapping("/reviews")
    public List<AdminReviewView> getAllReviews() {
        return reviewRepository.findAll().stream().map(r -> {
            AdminReviewView v = new AdminReviewView();
            v.reviewId = r.getReviewId();
            v.productId = r.getProductId();
            v.buyerId = r.getBuyerId();
            v.buyerName = userName(r.getBuyerId());
            v.rating = r.getRating();
            v.comment = r.getComment();
            v.createdAt = r.getCreatedAt();
            productRepository.findById(r.getProductId()).ifPresent(p -> v.productTitle = p.getTitle());
            return v;
        }).collect(Collectors.toList());
    }

    // DELETE /api/admin/reviews/7  - remove an inappropriate review
    @DeleteMapping("/reviews/{reviewId}")
    public ResponseEntity<?> deleteReview(@PathVariable Long reviewId) {
        if (!reviewRepository.existsById(reviewId)) return ResponseEntity.notFound().build();
        reviewRepository.deleteById(reviewId);
        return ResponseEntity.ok().build();
    }

    // ============================== DISCOUNT CODES ==============================

    public static class DiscountCodeRequest {
        public String codeName;
        public BigDecimal discountPercentage;
        public String expiryDate; // yyyy-MM-dd
        public Boolean isActive;
    }

    // GET /api/admin/discount-codes
    @GetMapping("/discount-codes")
    public List<DiscountCode> getDiscountCodes() {
        return discountCodeRepository.findAll();
    }

    private ResponseEntity<?> applyDiscountRequest(DiscountCode code, DiscountCodeRequest req) {
        if (blank(req.codeName)) return bad("Code name is required.");
        if (req.discountPercentage == null
                || req.discountPercentage.compareTo(BigDecimal.ZERO) <= 0
                || req.discountPercentage.compareTo(BigDecimal.valueOf(100)) > 0) {
            return bad("Discount must be between 0 and 100 percent.");
        }
        LocalDate expiry;
        try {
            expiry = LocalDate.parse(req.expiryDate);
        } catch (Exception e) {
            return bad("Pick a valid expiry date.");
        }
        String name = req.codeName.trim().toUpperCase();
        if (!name.equalsIgnoreCase(code.getCodeName() == null ? "" : code.getCodeName())
                && discountCodeRepository.existsByCodeNameIgnoreCase(name)) {
            return bad("That code already exists.");
        }
        code.setCodeName(name);
        code.setDiscountPercentage(req.discountPercentage);
        code.setExpiryDate(expiry);
        code.setIsActive(req.isActive == null || req.isActive);
        return ResponseEntity.ok(discountCodeRepository.save(code));
    }

    // POST /api/admin/discount-codes
    @PostMapping("/discount-codes")
    public ResponseEntity<?> createDiscountCode(@RequestBody DiscountCodeRequest req) {
        return applyDiscountRequest(new DiscountCode(), req);
    }

    // PUT /api/admin/discount-codes/2
    @PutMapping("/discount-codes/{codeId}")
    public ResponseEntity<?> updateDiscountCode(@PathVariable Long codeId, @RequestBody DiscountCodeRequest req) {
        DiscountCode code = discountCodeRepository.findById(codeId).orElse(null);
        if (code == null) return ResponseEntity.notFound().build();
        return applyDiscountRequest(code, req);
    }

    // DELETE /api/admin/discount-codes/2
    @DeleteMapping("/discount-codes/{codeId}")
    public ResponseEntity<?> deleteDiscountCode(@PathVariable Long codeId) {
        if (!discountCodeRepository.existsById(codeId)) return ResponseEntity.notFound().build();
        discountCodeRepository.deleteById(codeId);
        return ResponseEntity.ok().build();
    }

    // ============================== DELIVERY PARTNERS ==============================

    public static class DeliveryPartnerRequest {
        public String name;
        public String contactNo;
        public String vehicleType;
    }

    // GET /api/admin/delivery-partners
    @GetMapping("/delivery-partners")
    public List<DeliveryPartner> getDeliveryPartners() {
        return deliveryPartnerRepository.findAll();
    }

    private ResponseEntity<?> applyPartnerRequest(DeliveryPartner p, DeliveryPartnerRequest req) {
        if (blank(req.name) || blank(req.contactNo)) return bad("Name and contact number are required.");
        p.setName(req.name.trim());
        p.setContactNo(req.contactNo.trim());
        p.setVehicleType(blank(req.vehicleType) ? null : req.vehicleType.trim());
        return ResponseEntity.ok(deliveryPartnerRepository.save(p));
    }

    // POST /api/admin/delivery-partners
    @PostMapping("/delivery-partners")
    public ResponseEntity<?> createDeliveryPartner(@RequestBody DeliveryPartnerRequest req) {
        return applyPartnerRequest(new DeliveryPartner(), req);
    }

    // PUT /api/admin/delivery-partners/1
    @PutMapping("/delivery-partners/{partnerId}")
    public ResponseEntity<?> updateDeliveryPartner(@PathVariable Long partnerId, @RequestBody DeliveryPartnerRequest req) {
        DeliveryPartner p = deliveryPartnerRepository.findById(partnerId).orElse(null);
        if (p == null) return ResponseEntity.notFound().build();
        return applyPartnerRequest(p, req);
    }

    // DELETE /api/admin/delivery-partners/1  (their deliveries become unassigned)
    @DeleteMapping("/delivery-partners/{partnerId}")
    public ResponseEntity<?> deleteDeliveryPartner(@PathVariable Long partnerId) {
        if (!deliveryPartnerRepository.existsById(partnerId)) return ResponseEntity.notFound().build();
        deliveryRepository.findAll().stream()
                .filter(d -> partnerId.equals(d.getPartnerId()))
                .forEach(d -> { d.setPartnerId(null); d.setStatus(Delivery.DeliveryStatus.UNASSIGNED); deliveryRepository.save(d); });
        deliveryPartnerRepository.deleteById(partnerId);
        return ResponseEntity.ok().build();
    }

    // ============================== DELIVERIES ==============================

    public static class DeliveryView {
        public Long deliveryId;
        public Long orderId;
        public String orderStatus;
        public String buyerName;
        public String shippingAddress;
        public Long partnerId;
        public String partnerName;
        public String status;
        public LocalDateTime pickupTime;
        public LocalDateTime deliveryTime;
    }

    // GET /api/admin/deliveries
    @GetMapping("/deliveries")
    public List<DeliveryView> getDeliveries() {
        return deliveryRepository.findAll().stream().map(d -> {
            DeliveryView v = new DeliveryView();
            v.deliveryId = d.getDeliveryId();
            v.orderId = d.getOrderId();
            v.partnerId = d.getPartnerId();
            v.status = d.getStatus().name();
            v.pickupTime = d.getPickupTime();
            v.deliveryTime = d.getDeliveryTime();
            if (d.getPartnerId() != null) {
                deliveryPartnerRepository.findById(d.getPartnerId()).ifPresent(p -> v.partnerName = p.getName());
            }
            orderRepository.findById(d.getOrderId()).ifPresent(o -> {
                v.orderStatus = o.getOrderStatus().name();
                v.buyerName = userName(o.getBuyerId());
                v.shippingAddress = o.getShippingAddress();
            });
            return v;
        }).collect(Collectors.toList());
    }

    public static class DeliveryRequest {
        public Long orderId;
        public Long partnerId;
        public String status; // UNASSIGNED, ASSIGNED, PICKED_UP, DELIVERED
    }

    // POST /api/admin/deliveries  - create a delivery for an order (one per order)
    @PostMapping("/deliveries")
    public ResponseEntity<?> createDelivery(@RequestBody DeliveryRequest req) {
        Order order = req.orderId == null ? null : orderRepository.findById(req.orderId).orElse(null);
        if (order == null) return bad("Order not found.");
        if (order.getOrderStatus() == Order.OrderStatus.CANCELLED) return bad("That order was cancelled.");
        if (deliveryRepository.findByOrderId(req.orderId).isPresent()) return bad("That order already has a delivery.");
        if (req.partnerId != null && !deliveryPartnerRepository.existsById(req.partnerId)) return bad("Delivery partner not found.");

        Delivery d = new Delivery();
        d.setOrderId(req.orderId);
        d.setPartnerId(req.partnerId);
        d.setStatus(req.partnerId == null ? Delivery.DeliveryStatus.UNASSIGNED : Delivery.DeliveryStatus.ASSIGNED);
        Delivery saved = deliveryRepository.save(d);
        notifyDeliveryChange(saved, req.partnerId != null, req.partnerId != null);
        return ResponseEntity.ok(saved);
    }

    // PUT /api/admin/deliveries/4  - reassign partner and/or move the delivery along.
    // Picked up -> order becomes SHIPPED; delivered -> order becomes DELIVERED,
    // so the buyer's tracking timeline stays in sync.
    @PutMapping("/deliveries/{deliveryId}")
    public ResponseEntity<?> updateDelivery(@PathVariable Long deliveryId, @RequestBody DeliveryRequest req) {
        Delivery d = deliveryRepository.findById(deliveryId).orElse(null);
        if (d == null) return ResponseEntity.notFound().build();
        if (req.partnerId != null && !deliveryPartnerRepository.existsById(req.partnerId)) return bad("Delivery partner not found.");

        Delivery.DeliveryStatus status;
        try {
            status = Delivery.DeliveryStatus.valueOf(req.status);
        } catch (Exception e) {
            return bad("Invalid delivery status.");
        }
        if (status != Delivery.DeliveryStatus.UNASSIGNED && req.partnerId == null) {
            return bad("Assign a delivery partner first.");
        }

        boolean partnerChanged = !java.util.Objects.equals(d.getPartnerId(), req.partnerId);
        boolean statusChanged = d.getStatus() != status;

        d.setPartnerId(req.partnerId);
        d.setStatus(status);
        if (status == Delivery.DeliveryStatus.PICKED_UP && d.getPickupTime() == null) d.setPickupTime(LocalDateTime.now());
        if (status == Delivery.DeliveryStatus.DELIVERED) {
            if (d.getPickupTime() == null) d.setPickupTime(LocalDateTime.now());
            d.setDeliveryTime(LocalDateTime.now());
        }
        deliveryRepository.save(d);

        Order order = orderRepository.findById(d.getOrderId()).orElse(null);
        if (order != null && order.getOrderStatus() != Order.OrderStatus.CANCELLED) {
            Order.OrderStatus before = order.getOrderStatus();
            if (status == Delivery.DeliveryStatus.PICKED_UP) order.setOrderStatus(Order.OrderStatus.SHIPPED);
            if (status == Delivery.DeliveryStatus.DELIVERED) order.setOrderStatus(Order.OrderStatus.DELIVERED);
            orderRepository.save(order);
            // Email the buyer only when the order status really changed (no duplicate emails)
            if (order.getOrderStatus() != before) {
                orderEmailService.orderStatusChanged(order.getOrderId(), order.getOrderStatus());
            }
        }
        notifyDeliveryChange(d, partnerChanged, statusChanged);
        return ResponseEntity.ok(d);
    }

    // DELETE /api/admin/deliveries/4
    @DeleteMapping("/deliveries/{deliveryId}")
    public ResponseEntity<?> deleteDelivery(@PathVariable Long deliveryId) {
        if (!deliveryRepository.existsById(deliveryId)) return ResponseEntity.notFound().build();
        deliveryRepository.deleteById(deliveryId);
        return ResponseEntity.ok().build();
    }

    // ============================== COMPLAINTS ==============================

    public static class ComplaintView {
        public Long complaintId;
        public Long orderId;
        public Long raisedBy;
        public String raisedByName;
        public Long againstUserId;
        public String againstUserName;
        public String description;
        public String status;
        public String resolutionNotes;
        public LocalDateTime createdAt;
        public LocalDateTime resolvedAt;
    }

    // GET /api/admin/complaints
    @GetMapping("/complaints")
    public List<ComplaintView> getComplaints() {
        return complaintRepository.findAll().stream().map(c -> {
            ComplaintView v = new ComplaintView();
            v.complaintId = c.getComplaintId();
            v.orderId = c.getOrderId();
            v.raisedBy = c.getRaisedBy();
            v.raisedByName = userName(c.getRaisedBy());
            v.againstUserId = c.getAgainstUserId();
            v.againstUserName = userName(c.getAgainstUserId());
            v.description = c.getDescription();
            v.status = c.getStatus().name();
            v.resolutionNotes = c.getResolutionNotes();
            v.createdAt = c.getCreatedAt();
            v.resolvedAt = c.getResolvedAt();
            return v;
        }).collect(Collectors.toList());
    }

    public static class ComplaintUpdateRequest {
        public String status; // OPEN, UNDER_REVIEW, RESOLVED, REJECTED
        public String resolutionNotes;
    }

    // PUT /api/admin/complaints/3
    @PutMapping("/complaints/{complaintId}")
    public ResponseEntity<?> updateComplaint(@PathVariable Long complaintId, @RequestBody ComplaintUpdateRequest req) {
        Complaint c = complaintRepository.findById(complaintId).orElse(null);
        if (c == null) return ResponseEntity.notFound().build();
        Complaint.ComplaintStatus status;
        try {
            status = Complaint.ComplaintStatus.valueOf(req.status);
        } catch (Exception e) {
            return bad("Invalid status.");
        }
        c.setStatus(status);
        c.setResolutionNotes(req.resolutionNotes);
        boolean closed = status == Complaint.ComplaintStatus.RESOLVED || status == Complaint.ComplaintStatus.REJECTED;
        c.setResolvedAt(closed ? LocalDateTime.now() : null);
        complaintRepository.save(c);

        if (closed) {
            Notification n = new Notification();
            n.setUserId(c.getRaisedBy());
            n.setType("COMPLAINT");
            n.setMessage("Your complaint #" + c.getComplaintId() + " was " + status.name().toLowerCase()
                    + (blank(req.resolutionNotes) ? "." : ": " + req.resolutionNotes.trim()));
            notificationRepository.save(n);
        }
        return ResponseEntity.ok(c);
    }

    // DELETE /api/admin/complaints/3
    @DeleteMapping("/complaints/{complaintId}")
    public ResponseEntity<?> deleteComplaint(@PathVariable Long complaintId) {
        if (!complaintRepository.existsById(complaintId)) return ResponseEntity.notFound().build();
        complaintRepository.deleteById(complaintId);
        return ResponseEntity.ok().build();
    }

    // ============================== NOTIFICATIONS ==============================

    public static class NotificationView {
        public Long notificationId;
        public Long userId;
        public String userName;
        public String type;
        public String message;
        public Boolean isRead;
        public LocalDateTime sentDate;
    }

    // GET /api/admin/notifications
    @GetMapping("/notifications")
    public List<NotificationView> getNotifications() {
        return notificationRepository.findAll().stream().map(n -> {
            NotificationView v = new NotificationView();
            v.notificationId = n.getNotificationId();
            v.userId = n.getUserId();
            v.userName = userName(n.getUserId());
            v.type = n.getType();
            v.message = n.getMessage();
            v.isRead = n.getIsRead();
            v.sentDate = n.getSentDate();
            return v;
        }).sorted((a, b) -> {
            if (a.sentDate == null || b.sentDate == null) return 0;
            return b.sentDate.compareTo(a.sentDate);
        }).collect(Collectors.toList());
    }

    public static class SendNotificationRequest {
        public String audience; // USER, ALL_BUYERS, ALL_SELLERS, EVERYONE
        public Long userId;     // only used when audience = USER
        public String type;
        public String message;
    }

    // POST /api/admin/notifications
    @PostMapping("/notifications")
    public ResponseEntity<?> sendNotification(@RequestBody SendNotificationRequest req) {
        if (blank(req.message)) return bad("Message is required.");
        String type = blank(req.type) ? "ANNOUNCEMENT" : req.type.trim().toUpperCase();
        String audience = blank(req.audience) ? "USER" : req.audience;

        List<User> targets;
        switch (audience) {
            case "ALL_BUYERS":
                targets = userRepository.findAll().stream().filter(u -> u.getRole() == User.Role.BUYER).collect(Collectors.toList());
                break;
            case "ALL_SELLERS":
                targets = userRepository.findAll().stream().filter(u -> u.getRole() == User.Role.SELLER).collect(Collectors.toList());
                break;
            case "EVERYONE":
                targets = userRepository.findAll().stream().filter(u -> u.getRole() != User.Role.ADMIN).collect(Collectors.toList());
                break;
            default:
                User one = req.userId == null ? null : userRepository.findById(req.userId).orElse(null);
                if (one == null) return bad("Pick a user to notify.");
                targets = List.of(one);
        }

        for (User u : targets) {
            Notification n = new Notification();
            n.setUserId(u.getUserId());
            n.setType(type);
            n.setMessage(req.message.trim());
            notificationRepository.save(n);
        }
        return ResponseEntity.ok("Sent to " + targets.size() + " user(s).");
    }

    // DELETE /api/admin/notifications/9
    @DeleteMapping("/notifications/{notificationId}")
    public ResponseEntity<?> deleteNotification(@PathVariable Long notificationId) {
        if (!notificationRepository.existsById(notificationId)) return ResponseEntity.notFound().build();
        notificationRepository.deleteById(notificationId);
        return ResponseEntity.ok().build();
    }
}