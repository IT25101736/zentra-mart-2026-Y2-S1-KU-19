package com.zentramart.backend.catalog.controller;

import com.zentramart.backend.account.model.User;
import com.zentramart.backend.account.repository.UserRepository;
import com.zentramart.backend.catalog.model.Favorite;
import com.zentramart.backend.catalog.repository.FavoriteRepository;
import com.zentramart.backend.listing.model.Product;
import com.zentramart.backend.listing.repository.ProductRepository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/favorites")
@CrossOrigin(origins = "*")
public class FavoriteController {

    private final FavoriteRepository favoriteRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;

    @Autowired
    public FavoriteController(FavoriteRepository favoriteRepository, ProductRepository productRepository,
                              UserRepository userRepository) {
        this.favoriteRepository = favoriteRepository;
        this.productRepository = productRepository;
        this.userRepository = userRepository;
    }

    public static class FavoriteView {
        public Long favoriteId;
        public Long productId;
        public String title;
        public BigDecimal price;
        public String imageUrl;
        public Integer stockQty;
        public String status;
        public LocalDateTime addedAt;
    }

    @GetMapping("/{buyerId}")
    public List<FavoriteView> getFavorites(@PathVariable Long buyerId) {
        return favoriteRepository.findByBuyerIdOrderByCreatedAtDesc(buyerId).stream().map(f -> {
            Product p = productRepository.findById(f.getProductId()).orElse(null);
            if (p == null) return null;
            FavoriteView v = new FavoriteView();
            v.favoriteId = f.getFavoriteId();
            v.productId = p.getProductId();
            v.title = p.getTitle();
            v.price = p.getPrice();
            v.imageUrl = p.getImageUrl();
            v.stockQty = p.getStockQty();
            v.status = p.getStatus().name();
            v.addedAt = f.getCreatedAt();
            return v;
        }).filter(Objects::nonNull).collect(Collectors.toList());
    }

    @GetMapping("/{buyerId}/ids")
    public List<Long> getFavoriteIds(@PathVariable Long buyerId) {
        return favoriteRepository.findByBuyerIdOrderByCreatedAtDesc(buyerId).stream()
                .map(Favorite::getProductId)
                .collect(Collectors.toList());
    }

    public static class FavoriteRequest {
        public Long buyerId;
        public Long productId;
    }

    @PostMapping
    public ResponseEntity<?> addFavorite(@RequestBody FavoriteRequest req) {
        User buyer = req.buyerId == null ? null : userRepository.findById(req.buyerId).orElse(null);
        if (buyer == null || buyer.getRole() != User.Role.BUYER) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Only buyers can save favorites.");
        }
        if (req.productId == null || !productRepository.existsById(req.productId)) {
            return ResponseEntity.notFound().build();
        }
        Favorite existing = favoriteRepository.findByBuyerIdAndProductId(req.buyerId, req.productId).orElse(null);
        if (existing != null) return ResponseEntity.ok(existing);

        Favorite f = new Favorite();
        f.setBuyerId(req.buyerId);
        f.setProductId(req.productId);
        return ResponseEntity.ok(favoriteRepository.save(f));
    }

    @DeleteMapping("/{buyerId}/{productId}")
    public ResponseEntity<?> removeFavorite(@PathVariable Long buyerId, @PathVariable Long productId) {
        Favorite existing = favoriteRepository.findByBuyerIdAndProductId(buyerId, productId).orElse(null);
        if (existing == null) return ResponseEntity.notFound().build();
        favoriteRepository.delete(existing);
        return ResponseEntity.ok().build();
    }
}