
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
@RequestMapping("/api/products/{productId}/images")
@CrossOrigin(origins = "*")
public class ProductImageController {

    private final ProductImageRepository productImageRepository;
    private final ProductRepository productRepository;

    @Autowired
    public ProductImageController(ProductImageRepository productImageRepository, ProductRepository productRepository) {
        this.productImageRepository = productImageRepository;
        this.productRepository = productRepository;
    }

    // GET http://localhost:8080/api/products/5/images
    @GetMapping
    public List<ProductImage> getImages(@PathVariable Long productId) {
        return productImageRepository.findByProductId(productId);
    }

    // POST http://localhost:8080/api/products/5/images?sellerId=2  (multipart, field name "files", can send several at once)
    @PostMapping
    public ResponseEntity<?> uploadImages(@PathVariable Long productId,
                                          @RequestParam("files") List<MultipartFile> files,
                                          @RequestParam Long sellerId) throws IOException {
        Product product = productRepository.findById(productId).orElse(null);
        if (product == null) {
            return ResponseEntity.notFound().build();
        }
        if (!product.getSellerId().equals(sellerId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("You can only update your own listings.");
        }

        String uploadDir = "uploads";
        Files.createDirectories(Paths.get(uploadDir));

        boolean alreadyHasImages = !productImageRepository.findByProductId(productId).isEmpty();

        for (MultipartFile file : files) {
            String original = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
            String extension = original.contains(".") ? original.substring(original.lastIndexOf('.')) : "";
            String filename = "product_" + productId + "_" + System.nanoTime() + extension;

            Path filepath = Paths.get(uploadDir, filename);
            Files.copy(file.getInputStream(), filepath, StandardCopyOption.REPLACE_EXISTING);

            ProductImage img = new ProductImage();
            img.setProductId(productId);
            img.setImageUrl("/uploads/" + filename);
            img.setIsPrimary(!alreadyHasImages);
            productImageRepository.save(img);

            if (!alreadyHasImages) {
                product.setImageUrl(img.getImageUrl());
                alreadyHasImages = true; // only the very first image ever uploaded becomes primary
            }
        }
        productRepository.save(product);

        return ResponseEntity.ok(productImageRepository.findByProductId(productId));
    }

    // DELETE http://localhost:8080/api/products/5/images/12?sellerId=2
    @DeleteMapping("/{imageId}")
    public ResponseEntity<?> deleteImage(@PathVariable Long productId, @PathVariable Long imageId, @RequestParam Long sellerId) {
        Product product = productRepository.findById(productId).orElse(null);
        if (product == null) {
            return ResponseEntity.notFound().build();
        }
        if (!product.getSellerId().equals(sellerId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("You can only update your own listings.");
        }
        ProductImage image = productImageRepository.findById(imageId).orElse(null);
        if (image == null || !image.getProductId().equals(productId)) {
            return ResponseEntity.notFound().build();
        }

        boolean wasPrimary = Boolean.TRUE.equals(image.getIsPrimary());
        productImageRepository.deleteById(imageId);

        List<ProductImage> remaining = productImageRepository.findByProductId(productId);
        if (wasPrimary) {
            if (!remaining.isEmpty()) {
                ProductImage newPrimary = remaining.get(0);
                newPrimary.setIsPrimary(true);
                productImageRepository.save(newPrimary);
                product.setImageUrl(newPrimary.getImageUrl());
            } else {
                product.setImageUrl(null);
            }
            productRepository.save(product);
        }
        return ResponseEntity.ok(remaining);
    }
}