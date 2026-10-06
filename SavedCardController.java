package com.zentramart.backend.cart.controller;

import com.zentramart.backend.account.model.User;
import com.zentramart.backend.account.repository.UserRepository;
import com.zentramart.backend.cart.model.SavedCard;
import com.zentramart.backend.cart.repository.SavedCardRepository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.YearMonth;
import java.util.List;

// Saved cards for faster checkout. The browser sends the full number once,
// this controller keeps only the brand + last 4 digits + expiry, and throws
// the rest away. The CVV is never sent here at all.
@RestController
@RequestMapping("/api/cards")
@CrossOrigin(origins = "*")
public class SavedCardController {

    private final SavedCardRepository savedCardRepository;
    private final UserRepository userRepository;

    @Autowired
    public SavedCardController(SavedCardRepository savedCardRepository, UserRepository userRepository) {
        this.savedCardRepository = savedCardRepository;
        this.userRepository = userRepository;
    }

    private String detectBrand(String digits) {
        if (digits.startsWith("4")) return "Visa";
        if (digits.startsWith("34") || digits.startsWith("37")) return "Amex";
        int first2 = Integer.parseInt(digits.substring(0, 2));
        int first4 = Integer.parseInt(digits.substring(0, 4));
        if ((first2 >= 51 && first2 <= 55) || (first4 >= 2221 && first4 <= 2720)) return "Mastercard";
        return "Card";
    }

    // GET /api/cards/5 - this buyer's saved cards, newest first
    @GetMapping("/{userId}")
    public List<SavedCard> getCards(@PathVariable Long userId) {
        return savedCardRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    public static class SaveCardRequest {
        public Long userId;
        public String cardholderName;
        public String cardNumber;   // used only to work out brand + last 4, never stored
        public Integer expiryMonth;
        public Integer expiryYear;  // 4 digits, e.g. 2029
    }

    // POST /api/cards
    @PostMapping
    public ResponseEntity<?> saveCard(@RequestBody SaveCardRequest req) {
        User user = req.userId == null ? null : userRepository.findById(req.userId).orElse(null);
        if (user == null || user.getRole() != User.Role.BUYER) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Only buyers can save cards.");
        }
        String digits = req.cardNumber == null ? "" : req.cardNumber.replaceAll("[^0-9]", "");
        if (digits.length() < 13 || digits.length() > 19) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Card number looks invalid.");
        }
        if (req.cardholderName == null || req.cardholderName.trim().isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Cardholder name is required.");
        }
        if (req.expiryMonth == null || req.expiryYear == null || req.expiryMonth < 1 || req.expiryMonth > 12
                || YearMonth.of(req.expiryYear, req.expiryMonth).isBefore(YearMonth.now())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("This card has expired.");
        }

        String last4 = digits.substring(digits.length() - 4);
        String brand = detectBrand(digits);

        // Don't save the same card twice
        for (SavedCard c : savedCardRepository.findByUserIdOrderByCreatedAtDesc(req.userId)) {
            if (c.getLast4().equals(last4) && c.getBrand().equals(brand)
                    && c.getExpiryMonth().equals(req.expiryMonth) && c.getExpiryYear().equals(req.expiryYear)) {
                return ResponseEntity.ok(c);
            }
        }

        SavedCard card = new SavedCard();
        card.setUserId(req.userId);
        card.setCardholderName(req.cardholderName.trim());
        card.setBrand(brand);
        card.setLast4(last4);
        card.setExpiryMonth(req.expiryMonth);
        card.setExpiryYear(req.expiryYear);
        return ResponseEntity.ok(savedCardRepository.save(card));
    }

    // DELETE /api/cards/5/3   (user 5 removes card 3)
    @DeleteMapping("/{userId}/{cardId}")
    public ResponseEntity<?> deleteCard(@PathVariable Long userId, @PathVariable Long cardId) {
        SavedCard card = savedCardRepository.findById(cardId).orElse(null);
        if (card == null || !card.getUserId().equals(userId)) return ResponseEntity.notFound().build();
        savedCardRepository.delete(card);
        return ResponseEntity.ok().build();
    }
}