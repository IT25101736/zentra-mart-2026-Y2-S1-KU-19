package com.zentramart.backend;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

// Lets a seller remove their own listings - even ones that have been ordered before.
//
//  - Never ordered  -> deleted for good. The database automatically removes its photos,
//                      reviews, cart and favorite entries (they use ON DELETE CASCADE).
//  - Ordered before -> "removed": status becomes ARCHIVED, it disappears from the shop,
//                      from everyone's cart and favorites, and from the seller's main list.
//                      The row itself stays, because past orders (order_items) still point
//                      at it - receipts, order history and admin reports keep the product name.
//                      The seller can restore it any time.
@RestController
@RequestMapping("/api/seller/products")
@CrossOrigin(origins = "*")
public class SellerListingController {

    private final ProductRepository productRepository;
    private final JdbcTemplate jdbc;

    public SellerListingController(ProductRepository productRepository, JdbcTemplate jdbc) {
        this.productRepository = productRepository;
        this.jdbc = jdbc;
    }

    private static Map<String, Object> result(String outcome, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("result", outcome);
        body.put("message", message);
        return body;
    }

    // DELETE http://localhost:8080/api/seller/products/12?sellerId=5
    @Transactional
    @DeleteMapping("/{productId}")
    public ResponseEntity<?> removeListing(@PathVariable Long productId, @RequestParam Long sellerId) {
        Product product = productRepository.findById(productId).orElse(null);
        if (product == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("That listing doesn't exist any more. Refresh the page.");
        }
        if (!sellerId.equals(product.getSellerId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("You can only remove your own listings.");
        }

        Integer timesOrdered = jdbc.queryForObject(
                "SELECT COUNT(*) FROM order_items WHERE product_id = ?", Integer.class, productId);

        if (timesOrdered != null && timesOrdered > 0) {
            product.setStatus(Product.ProductStatus.ARCHIVED);
            productRepository.save(product);
            // Take it out of every buyer's cart and favorites so nobody can try to buy it
            jdbc.update("DELETE FROM cart_items WHERE product_id = ?", productId);
            jdbc.update("DELETE FROM favorites WHERE product_id = ?", productId);
            return ResponseEntity.ok(result("REMOVED",
                    "\"" + product.getTitle() + "\" was removed from your shop. It's kept in your "
                            + "Removed listings because it appears in past orders."));
        }

        productRepository.delete(product);
        return ResponseEntity.ok(result("DELETED", "\"" + product.getTitle() + "\" was deleted."));
    }

    // PUT http://localhost:8080/api/seller/products/12/restore?sellerId=5
    // Puts a removed listing back in the shop.
    @PutMapping("/{productId}/restore")
    public ResponseEntity<?> restoreListing(@PathVariable Long productId, @RequestParam Long sellerId) {
        Product product = productRepository.findById(productId).orElse(null);
        if (product == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("That listing doesn't exist any more.");
        }
        if (!sellerId.equals(product.getSellerId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("You can only restore your own listings.");
        }
        boolean inStock = product.getStockQty() != null && product.getStockQty() > 0;
        product.setStatus(inStock ? Product.ProductStatus.ACTIVE : Product.ProductStatus.SOLD);
        productRepository.save(product);
        return ResponseEntity.ok(result(inStock ? "ACTIVE" : "SOLD", inStock
                ? "\"" + product.getTitle() + "\" is back in your shop."
                : "\"" + product.getTitle() + "\" was restored but has no stock - edit it to add stock."));
    }
}