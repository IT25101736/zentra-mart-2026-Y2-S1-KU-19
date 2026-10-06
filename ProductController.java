package com.zentramart.backend;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;

@RestController
@RequestMapping("/api/products")
@CrossOrigin(origins = "*")
public class ProductController {

    private final ProductRepository productRepository;

    @Autowired
    public ProductController(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @GetMapping
    public List<Product> getAllProducts() {
        return productRepository.findAll();
    }

    @GetMapping("/{id}")
    public Product getProductById(@PathVariable Long id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Product not found with id: " + id));
    }

    @GetMapping("/search")
    public List<Product> searchProducts(@RequestParam String keyword) {
        return productRepository.findByTitleContainingIgnoreCase(keyword);
    }

    @GetMapping("/seller/{sellerId}")
    public List<Product> getProductsBySeller(@PathVariable Long sellerId) {
        return productRepository.findBySellerId(sellerId);
    }

    @PostMapping
    public Product createProduct(@RequestBody Product product) {
        return productRepository.save(product);
    }

    // PUT http://localhost:8080/api/products/5?sellerId=2
    // Edit a listing's title/description/price/stock/category. Only the owning seller may edit it.
    @PutMapping("/{id}")
    public ResponseEntity<?> updateProduct(@PathVariable Long id, @RequestBody Product updated, @RequestParam Long sellerId) {
        Product product = productRepository.findById(id).orElse(null);
        if (product == null) {
            return ResponseEntity.notFound().build();
        }
        if (!product.getSellerId().equals(sellerId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("You can only edit your own listings.");
        }

        product.setTitle(updated.getTitle());
        product.setDescription(updated.getDescription());
        product.setPrice(updated.getPrice());
        product.setStockQty(updated.getStockQty());
        product.setCategoryId(updated.getCategoryId());
        if (updated.getStatus() != null) {
            product.setStatus(updated.getStatus());
        }

        return ResponseEntity.ok(productRepository.save(product));
    }

    // POST http://localhost:8080/api/products/5/image?sellerId=2  (multipart form-data, field name "file")
    // Uploads a real photo for a listing and saves its path on the product.
    @PostMapping("/{id}/image")
    public ResponseEntity<?> uploadProductImage(@PathVariable Long id,
                                                @RequestParam("file") MultipartFile file,
                                                @RequestParam Long sellerId) throws IOException {
        Product product = productRepository.findById(id).orElse(null);
        if (product == null) {
            return ResponseEntity.notFound().build();
        }
        if (!product.getSellerId().equals(sellerId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("You can only update your own listings.");
        }

        String uploadDir = "uploads";
        Files.createDirectories(Paths.get(uploadDir));

        String original = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        String extension = original.contains(".") ? original.substring(original.lastIndexOf('.')) : "";
        String filename = "product_" + id + "_" + System.currentTimeMillis() + extension;

        Path filepath = Paths.get(uploadDir, filename);
        Files.copy(file.getInputStream(), filepath, StandardCopyOption.REPLACE_EXISTING);

        product.setImageUrl("/uploads/" + filename);
        return ResponseEntity.ok(productRepository.save(product));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteProduct(@PathVariable Long id, @RequestParam Long sellerId) {
        Product product = productRepository.findById(id).orElse(null);
        if (product == null) {
            return ResponseEntity.notFound().build();
        }
        if (!product.getSellerId().equals(sellerId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("You can only delete your own listings.");
        }
        productRepository.deleteById(id);
        return ResponseEntity.ok().build();
    }
}