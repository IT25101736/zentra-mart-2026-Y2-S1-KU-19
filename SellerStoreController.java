package com.zentramart.backend.catalog.controller;

import com.zentramart.backend.account.model.User;
import com.zentramart.backend.account.repository.UserRepository;
import com.zentramart.backend.catalog.model.Review;
import com.zentramart.backend.catalog.repository.ReviewRepository;
import com.zentramart.backend.listing.model.Product;
import com.zentramart.backend.listing.repository.ProductRepository;
import com.zentramart.backend.order.model.Order;
import com.zentramart.backend.order.model.OrderItem;
import com.zentramart.backend.order.repository.OrderItemRepository;
import com.zentramart.backend.order.repository.OrderRepository;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

// Public seller store page (store.html?seller=5).
// Everything here is REAL data: listings, items sold, and the average rating
// from buyers' reviews of this seller's products.
@RestController
@RequestMapping("/api/sellers")
@CrossOrigin(origins = "*")
public class SellerStoreController {

    private final UserRepository userRepository;
    private final ProductRepository productRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderRepository orderRepository;
    private final ReviewRepository reviewRepository;

    public SellerStoreController(UserRepository userRepository, ProductRepository productRepository,
                                 OrderItemRepository orderItemRepository, OrderRepository orderRepository,
                                 ReviewRepository reviewRepository) {
        this.userRepository = userRepository;
        this.productRepository = productRepository;
        this.orderItemRepository = orderItemRepository;
        this.orderRepository = orderRepository;
        this.reviewRepository = reviewRepository;
    }

    public static class StoreProduct {
        public Long productId;
        public String title;
        public BigDecimal price;
        public String imageUrl;
        public Integer stockQty;
        public Integer categoryId;
    }

    public static class StoreView {
        public Long sellerId;
        public String name;
        public String city;
        public String country;
        public LocalDateTime memberSince;
        public String photo;          // small profile picture (data URL) or null
        public int activeListings;
        public int itemsSold;         // from real orders (cancelled orders don't count)
        public BigDecimal avgRating;  // null when there are no reviews yet
        public int reviewCount;
        public List<StoreProduct> products = new ArrayList<>();
    }

    // GET http://localhost:8080/api/sellers/5/store
    @GetMapping("/{sellerId}/store")
    public ResponseEntity<?> getStore(@PathVariable Long sellerId) {
        User seller = userRepository.findById(sellerId).orElse(null);
        if (seller == null || seller.getRole() != User.Role.SELLER || seller.getStatus() == User.Status.DEACTIVATED) {
            return ResponseEntity.notFound().build();
        }

        StoreView v = new StoreView();
        v.sellerId = seller.getUserId();
        v.name = seller.getFullName();
        v.city = seller.getCity();
        v.country = seller.getCountry();
        v.memberSince = seller.getCreatedAt();
        v.photo = seller.getProfileImage();

        // Listings + ratings
        int ratingSum = 0;
        for (Product p : productRepository.findBySellerId(sellerId)) {
            for (Review r : reviewRepository.findByProductId(p.getProductId())) {
                if (r.getRating() != null) {
                    ratingSum += r.getRating();
                    v.reviewCount++;
                }
            }
            if (p.getStatus() == Product.ProductStatus.ACTIVE) {
                StoreProduct sp = new StoreProduct();
                sp.productId = p.getProductId();
                sp.title = p.getTitle();
                sp.price = p.getPrice();
                sp.imageUrl = p.getImageUrl();
                sp.stockQty = p.getStockQty();
                sp.categoryId = p.getCategoryId();
                v.products.add(sp);
            }
        }
        v.activeListings = v.products.size();
        if (v.reviewCount > 0) {
            v.avgRating = BigDecimal.valueOf(ratingSum)
                    .divide(BigDecimal.valueOf(v.reviewCount), 1, RoundingMode.HALF_UP);
        }

        // Items sold (skip cancelled orders)
        for (OrderItem oi : orderItemRepository.findAll()) {
            if (!sellerId.equals(oi.getSellerId())) continue;
            boolean cancelled = orderRepository.findById(oi.getOrderId())
                    .map(o -> o.getOrderStatus() == Order.OrderStatus.CANCELLED)
                    .orElse(true);
            if (!cancelled && oi.getQuantity() != null) v.itemsSold += oi.getQuantity();
        }

        // Newest listings first
        v.products.sort((a, b) -> Long.compare(b.productId, a.productId));
        return ResponseEntity.ok(v);
    }
}