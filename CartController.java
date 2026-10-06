package com.zentramart.backend.cart.controller;

import com.zentramart.backend.cart.model.CartItem;
import com.zentramart.backend.cart.repository.CartItemRepository;
import com.zentramart.backend.listing.model.Product;
import com.zentramart.backend.listing.repository.ProductRepository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/cart")
@CrossOrigin(origins = "*")
public class CartController {

    private final CartItemRepository cartItemRepository;
    private final ProductRepository productRepository;

    @Autowired
    public CartController(CartItemRepository cartItemRepository, ProductRepository productRepository) {
        this.cartItemRepository = cartItemRepository;
        this.productRepository = productRepository;
    }

    public static class CartItemView {
        public Long cartItemId;
        public Long productId;
        public String title;
        public BigDecimal price;
        public String imageUrl;
        public Integer stockQty;
        public Integer quantity;
        public BigDecimal lineTotal;
    }

    private CartItemView toView(CartItem item, Product product) {
        CartItemView v = new CartItemView();
        v.cartItemId = item.getCartItemId();
        v.productId = item.getProductId();
        v.quantity = item.getQuantity();
        if (product != null) {
            v.title = product.getTitle();
            v.price = product.getPrice();
            v.imageUrl = product.getImageUrl();
            v.stockQty = product.getStockQty();
            v.lineTotal = product.getPrice().multiply(BigDecimal.valueOf(item.getQuantity()));
        }
        return v;
    }

    @GetMapping("/{buyerId}")
    public List<CartItemView> getCart(@PathVariable Long buyerId) {
        return cartItemRepository.findByBuyerId(buyerId).stream()
                .map(item -> toView(item, productRepository.findById(item.getProductId()).orElse(null)))
                .collect(Collectors.toList());
    }

    public static class AddToCartRequest {
        public Long buyerId;
        public Long productId;
        public Integer quantity;
    }

    @PostMapping
    public ResponseEntity<?> addToCart(@RequestBody AddToCartRequest req) {
        Product product = productRepository.findById(req.productId).orElse(null);
        if (product == null) return ResponseEntity.notFound().build();
        if (product.getStatus() != Product.ProductStatus.ACTIVE)
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("This item is no longer available.");

        int requestedQty = (req.quantity == null || req.quantity < 1) ? 1 : req.quantity;
        Optional<CartItem> existing = cartItemRepository.findByBuyerIdAndProductId(req.buyerId, req.productId);
        CartItem item = existing.orElseGet(CartItem::new);
        int newQty = (existing.isPresent() ? item.getQuantity() : 0) + requestedQty;

        if (newQty > product.getStockQty())
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Only " + product.getStockQty() + " left in stock.");

        item.setBuyerId(req.buyerId);
        item.setProductId(req.productId);
        item.setQuantity(newQty);
        cartItemRepository.save(item);
        return ResponseEntity.ok(toView(item, product));
    }

    public static class UpdateQuantityRequest {
        public Integer quantity;
    }

    @PutMapping("/{cartItemId}")
    public ResponseEntity<?> updateQuantity(@PathVariable Long cartItemId, @RequestBody UpdateQuantityRequest req) {
        CartItem item = cartItemRepository.findById(cartItemId).orElse(null);
        if (item == null) return ResponseEntity.notFound().build();
        if (req.quantity == null || req.quantity < 1)
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Quantity must be at least 1.");
        Product product = productRepository.findById(item.getProductId()).orElse(null);
        if (product != null && req.quantity > product.getStockQty())
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Only " + product.getStockQty() + " left in stock.");
        item.setQuantity(req.quantity);
        cartItemRepository.save(item);
        return ResponseEntity.ok(toView(item, product));
    }

    @DeleteMapping("/{cartItemId}")
    public ResponseEntity<?> removeItem(@PathVariable Long cartItemId) {
        if (!cartItemRepository.existsById(cartItemId)) return ResponseEntity.notFound().build();
        cartItemRepository.deleteById(cartItemId);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/buyer/{buyerId}")
    public ResponseEntity<?> clearCart(@PathVariable Long buyerId) {
        cartItemRepository.deleteByBuyerId(buyerId);
        return ResponseEntity.ok().build();
    }
}