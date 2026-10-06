package com.zentramart.backend.account.controller;

import com.zentramart.backend.account.model.PasswordResetOtp;
import com.zentramart.backend.account.model.User;
import com.zentramart.backend.account.repository.PasswordResetOtpRepository;
import com.zentramart.backend.account.repository.UserRepository;
import com.zentramart.backend.cart.model.CartItem;
import com.zentramart.backend.cart.model.SavedCard;
import com.zentramart.backend.cart.repository.CartItemRepository;
import com.zentramart.backend.cart.repository.SavedCardRepository;
import com.zentramart.backend.catalog.model.Favorite;
import com.zentramart.backend.catalog.repository.FavoriteRepository;
import com.zentramart.backend.common.service.EmailService;
import com.zentramart.backend.common.util.Validation;
import com.zentramart.backend.listing.model.Product;
import com.zentramart.backend.listing.repository.ProductRepository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// Account security: change password, forgot password (email OTP), delete account.
@RestController
@RequestMapping("/api/account")
@CrossOrigin(origins = "*")
public class AccountController {

    private static final int OTP_MINUTES = 5;           // code is valid for 5 minutes
    private static final int RESEND_SECONDS = 60;       // wait 60s before asking for a new code
    private static final int MAX_ATTEMPTS = 5;          // 5 wrong tries and the code is dead
    private static final int RESET_WINDOW_MINUTES = 10; // time to choose a new password after verifying

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final CartItemRepository cartItemRepository;
    private final FavoriteRepository favoriteRepository;
    private final ProductRepository productRepository;
    private final SavedCardRepository savedCardRepository;
    private final PasswordResetOtpRepository otpRepository;
    private final EmailService emailService;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public AccountController(UserRepository userRepository, PasswordEncoder passwordEncoder,
                             CartItemRepository cartItemRepository, FavoriteRepository favoriteRepository,
                             ProductRepository productRepository, SavedCardRepository savedCardRepository,
                             PasswordResetOtpRepository otpRepository, EmailService emailService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.cartItemRepository = cartItemRepository;
        this.favoriteRepository = favoriteRepository;
        this.productRepository = productRepository;
        this.savedCardRepository = savedCardRepository;
        this.otpRepository = otpRepository;
        this.emailService = emailService;
    }

    private static ResponseEntity<?> bad(String message) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(message);
    }

    // Keeps only the digits so "+94 77-123 4567" and "94771234567" match
    private String digitsOnly(String s) {
        return s == null ? "" : s.replaceAll("[^0-9]", "");
    }

    // "kasun.perera@gmail.com" -> "ka*********@gmail.com"
    private String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 2) return email;
        return email.substring(0, 2) + "*".repeat(at - 2) + email.substring(at);
    }

    // ---------- Change password (logged in) ----------

    public static class ChangePasswordRequest {
        public String currentPassword;
        public String newPassword;
    }

    // PUT /api/account/5/password
    @PutMapping("/{userId}/password")
    public ResponseEntity<?> changePassword(@PathVariable Long userId, @RequestBody ChangePasswordRequest req) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) return ResponseEntity.notFound().build();

        if (req.currentPassword == null || !passwordEncoder.matches(req.currentPassword, user.getPasswordHash())) {
            return bad("Your current password is incorrect.");
        }
        String problem = Validation.checkPassword(req.newPassword);
        if (problem != null) return bad(problem);
        if (passwordEncoder.matches(req.newPassword, user.getPasswordHash())) {
            return bad("Your new password must be different from the current one.");
        }
        user.setPasswordHash(passwordEncoder.encode(req.newPassword));
        userRepository.save(user);
        return ResponseEntity.ok("Password updated.");
    }

    // ---------- Forgot password, step 1: email + phone -> send a code ----------

    public static class ForgotRequest {
        public String email;
        public String phone;
    }

    // POST /api/account/forgot-password/request
    @PostMapping("/forgot-password/request")
    public ResponseEntity<?> requestCode(@RequestBody ForgotRequest req) {
        String wrong = "Email and phone number don't match any account.";
        if (Validation.checkEmail(req.email) != null || req.phone == null || digitsOnly(req.phone).isEmpty()) {
            return bad(wrong);
        }
        User user = userRepository.findByEmailIgnoreCase(req.email.trim()).orElse(null);
        if (user == null) return bad(wrong);
        if (user.getPhone() == null || digitsOnly(user.getPhone()).isEmpty()) {
            return bad("This account has no phone number saved, so it can't be reset here. Please contact the admin.");
        }
        if (!digitsOnly(user.getPhone()).equals(digitsOnly(req.phone))) return bad(wrong);
        if (user.getStatus() != User.Status.ACTIVE) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("This account is suspended or deactivated.");
        }

        // Don't allow spamming "send code"
        PasswordResetOtp last = otpRepository.findTopByUserIdOrderByOtpIdDesc(user.getUserId()).orElse(null);
        if (last != null && !last.getUsed() && last.getCreatedAt() != null) {
            long secondsSince = Duration.between(last.getCreatedAt(), LocalDateTime.now()).getSeconds();
            if (secondsSince < RESEND_SECONDS) {
                return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                        .body("Please wait " + (RESEND_SECONDS - secondsSince) + " seconds before asking for a new code.");
            }
            last.setUsed(true); // the old code stops working once a new one is sent
            otpRepository.save(last);
        }

        String code = String.format("%04d", random.nextInt(10000));
        PasswordResetOtp otp = new PasswordResetOtp();
        otp.setUserId(user.getUserId());
        otp.setOtpHash(passwordEncoder.encode(code));
        otp.setExpiresAt(LocalDateTime.now().plusMinutes(OTP_MINUTES));
        otpRepository.save(otp);

        String firstName = user.getFullName() == null ? "there" : user.getFullName().split(" ")[0];
        boolean sent = emailService.send(
                user.getEmail(),
                "Your Zentra Mart password reset code: " + code,
                "Hi " + firstName + ",\n\n"
                        + "Use this code to reset your Zentra Mart password:\n\n"
                        + "        " + code + "\n\n"
                        + "It expires in " + OTP_MINUTES + " minutes. If you didn't ask to reset your password, "
                        + "you can ignore this email - your password won't change.\n\n"
                        + "- Zentra Mart");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("maskedEmail", maskEmail(user.getEmail()));
        body.put("resendAfterSeconds", RESEND_SECONDS);
        body.put("expiresInMinutes", OTP_MINUTES);
        body.put("emailSent", sent);
        return ResponseEntity.ok(body);
    }

    // ---------- Forgot password, step 2: check the code ----------

    public static class VerifyRequest {
        public String email;
        public String otp;
    }

    // POST /api/account/forgot-password/verify
    @PostMapping("/forgot-password/verify")
    public ResponseEntity<?> verifyCode(@RequestBody VerifyRequest req) {
        if (req.email == null || req.otp == null || !req.otp.matches("\\d{4}")) {
            return bad("Enter the 4-digit code from your email.");
        }
        User user = userRepository.findByEmailIgnoreCase(req.email.trim()).orElse(null);
        PasswordResetOtp otp = user == null ? null
                : otpRepository.findTopByUserIdOrderByOtpIdDesc(user.getUserId()).orElse(null);
        if (otp == null || otp.getUsed()) return bad("This code is no longer valid. Please request a new one.");
        if (otp.getExpiresAt().isBefore(LocalDateTime.now())) return bad("This code has expired. Please request a new one.");
        if (otp.getAttempts() >= MAX_ATTEMPTS) return bad("Too many wrong tries. Please request a new code.");

        if (!passwordEncoder.matches(req.otp, otp.getOtpHash())) {
            otp.setAttempts(otp.getAttempts() + 1);
            otpRepository.save(otp);
            int left = MAX_ATTEMPTS - otp.getAttempts();
            return bad(left > 0 ? "Incorrect code. " + left + " tr" + (left == 1 ? "y" : "ies") + " left."
                    : "Too many wrong tries. Please request a new code.");
        }

        String token = UUID.randomUUID().toString().replace("-", "");
        otp.setVerified(true);
        otp.setResetToken(token);
        otp.setExpiresAt(LocalDateTime.now().plusMinutes(RESET_WINDOW_MINUTES));
        otpRepository.save(otp);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("resetToken", token);
        return ResponseEntity.ok(body);
    }

    // ---------- Forgot password, step 3: set the new password ----------

    public static class ResetRequest {
        public String email;
        public String resetToken;
        public String newPassword;
    }

    // POST /api/account/forgot-password/reset
    @PostMapping("/forgot-password/reset")
    public ResponseEntity<?> resetPassword(@RequestBody ResetRequest req) {
        String expired = "Your reset session has expired. Please start again.";
        if (req.email == null || req.resetToken == null) return bad(expired);
        User user = userRepository.findByEmailIgnoreCase(req.email.trim()).orElse(null);
        PasswordResetOtp otp = user == null ? null
                : otpRepository.findTopByUserIdOrderByOtpIdDesc(user.getUserId()).orElse(null);
        if (otp == null || otp.getUsed() || !otp.getVerified()
                || !req.resetToken.equals(otp.getResetToken())
                || otp.getExpiresAt().isBefore(LocalDateTime.now())) {
            return bad(expired);
        }

        String problem = Validation.checkPassword(req.newPassword);
        if (problem != null) return bad(problem);

        user.setPasswordHash(passwordEncoder.encode(req.newPassword));
        userRepository.save(user);
        otp.setUsed(true);
        otpRepository.save(otp);

        emailService.send(user.getEmail(), "Your Zentra Mart password was changed",
                "Your Zentra Mart password was just reset. If this wasn't you, contact the admin straight away.\n\n- Zentra Mart");
        return ResponseEntity.ok("Password reset. You can log in now.");
    }

    // ---------- Delete account ----------

    public static class DeleteAccountRequest {
        public String password;
    }

    // POST /api/account/5/delete
    // Soft delete: account DEACTIVATED, email freed, cart + favorites + saved cards
    // removed, and a seller's listings archived. Old orders stay intact.
    @Transactional
    @PostMapping("/{userId}/delete")
    public ResponseEntity<?> deleteAccount(@PathVariable Long userId, @RequestBody DeleteAccountRequest req) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) return ResponseEntity.notFound().build();

        if (user.getRole() == User.Role.ADMIN) return bad("The admin account can't be deleted.");
        if (req.password == null || !passwordEncoder.matches(req.password, user.getPasswordHash())) {
            return bad("Password is incorrect.");
        }

        List<CartItem> cart = cartItemRepository.findByBuyerId(userId);
        cartItemRepository.deleteAll(cart);

        List<Favorite> favorites = favoriteRepository.findByBuyerIdOrderByCreatedAtDesc(userId);
        favoriteRepository.deleteAll(favorites);

        List<SavedCard> cards = savedCardRepository.findByUserIdOrderByCreatedAtDesc(userId);
        savedCardRepository.deleteAll(cards);

        if (user.getRole() == User.Role.SELLER) {
            for (Product p : productRepository.findBySellerId(userId)) {
                p.setStatus(Product.ProductStatus.ARCHIVED);
                productRepository.save(p);
            }
        }

        String freedEmail = "deleted-" + userId + "-" + user.getEmail();
        if (freedEmail.length() > 150) freedEmail = freedEmail.substring(0, 150);
        user.setEmail(freedEmail);
        user.setStatus(User.Status.DEACTIVATED);
        userRepository.save(user);

        return ResponseEntity.ok("Your account has been deleted.");
    }
}