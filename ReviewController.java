package com.zentramart.backend.catalog.controller;

import com.zentramart.backend.account.repository.UserRepository;
import com.zentramart.backend.catalog.model.Review;
import com.zentramart.backend.catalog.repository.ReviewRepository;
import com.zentramart.backend.listing.repository.ProductRepository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/reviews")
@CrossOrigin(origins = "*")
public class ReviewController {

    private final ReviewRepository reviewRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;

    @Autowired
    public ReviewController(ReviewRepository reviewRepository, ProductRepository productRepository,
                            UserRepository userRepository) {
        this.reviewRepository = reviewRepository;
        this.productRepository = productRepository;
        this.userRepository = userRepository;
    }

    // Enriched review - adds the buyer's name so the frontend doesn't need a
    // second lookup per review.
    public static class ReviewView {
        public Long reviewId;
        public Long productId;
        public String productTitle;
        public Long buyerId;
        public String buyerName;
        public Integer rating;
        public String comment;
        public LocalDateTime createdAt;
    }

    private ReviewView toView(Review r) {
        ReviewView v = new ReviewView();
        v.reviewId = r.getReviewId();
        v.productId = r.getProductId();
        v.buyerId = r.getBuyerId();
        v.rating = r.getRating();
        v.comment = r.getComment();
        v.createdAt = r.getCreatedAt();
        productRepository.findById(r.getProductId()).ifPresent(p -> v.productTitle = p.getTitle());
        userRepository.findById(r.getBuyerId()).ifPresent(u -> v.buyerName = u.getFullName());
        return v;
    }

    public static class CreateReviewRequest {
        public Long buyerId;
        public Long productId;
        public Integer rating;
        public String comment;
    }

    // POST http://localhost:8080/api/reviews
    // body: { "buyerId": 5, "productId": 12, "rating": 4, "comment": "Good product" }
    // One review per buyer per product - resubmitting updates the existing one.
    @PostMapping
    public ResponseEntity<?> createReview(@RequestBody CreateReviewRequest req) {
        if (req.rating == null || req.rating < 1 || req.rating > 5) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Rating must be between 1 and 5.");
        }
        if (productRepository.findById(req.productId).isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Optional<Review> existing = reviewRepository.findByBuyerIdAndProductId(req.buyerId, req.productId);
        Review review = existing.orElseGet(Review::new);
        review.setBuyerId(req.buyerId);
        review.setProductId(req.productId);
        review.setRating(req.rating);
        review.setComment(req.comment);
        reviewRepository.save(review);

        return ResponseEntity.ok(toView(review));
    }

    // GET http://localhost:8080/api/reviews/product/12   (12 = productId)
    @GetMapping("/product/{productId}")
    public List<ReviewView> getReviewsForProduct(@PathVariable Long productId) {
        return reviewRepository.findByProductId(productId).stream()
                .map(this::toView)
                .collect(Collectors.toList());
    }
}
