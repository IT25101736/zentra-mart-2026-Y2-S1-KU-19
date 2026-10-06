package com.zentramart.backend.account.controller;

import com.zentramart.backend.account.model.User;
import com.zentramart.backend.account.repository.UserRepository;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/users")
@CrossOrigin(origins = "*")
public class ProfileController {

    private final UserRepository userRepository;

    // The profile page shrinks photos to 256x256 before uploading (about 20-60 KB).
    // This limit just blocks anything silly.
    private static final int MAX_PHOTO_BYTES = 1_500_000;

    @Autowired
    public ProfileController(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    // GET http://localhost:8080/api/users/5   (5 = userId)
    // Used to pre-fill the profile page. Password hash is never returned.
    // The JSON includes "hasPhoto": true/false (the photo itself comes from /photo below).
    @GetMapping("/{userId}")
    public ResponseEntity<?> getProfile(@PathVariable Long userId) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            return ResponseEntity.notFound().build();
        }
        user.setPasswordHash(null);
        return ResponseEntity.ok(user);
    }

    public static class UpdateProfileRequest {
        public String fullName;
        public String email;
        public String phone;
        public String address;
        public String city;
        public String country;
    }

    // PUT http://localhost:8080/api/users/5/profile
    @PutMapping("/{userId}/profile")
    public ResponseEntity<?> updateProfile(@PathVariable Long userId, @RequestBody UpdateProfileRequest req) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            return ResponseEntity.notFound().build();
        }

        if (req.fullName == null || req.fullName.trim().isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Full name is required.");
        }
        if (req.email == null || req.email.trim().isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Email is required.");
        }

        String newEmail = req.email.trim().toLowerCase();
        if (!newEmail.equalsIgnoreCase(user.getEmail()) && userRepository.existsByEmailIgnoreCase(newEmail)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("That email is already in use.");
        }

        user.setFullName(req.fullName.trim());
        user.setEmail(newEmail);
        user.setPhone(req.phone == null ? null : req.phone.trim());
        user.setAddress(req.address == null ? null : req.address.trim());
        user.setCity(req.city == null ? null : req.city.trim());
        user.setCountry(req.country == null ? null : req.country.trim());

        User saved = userRepository.save(user);
        saved.setPasswordHash(null);
        return ResponseEntity.ok(saved);
    }

    // ============================ PROFILE PHOTO ============================

    // GET http://localhost:8080/api/users/5/photo
    // Returns { "image": "data:image/jpeg;base64,..." }  or  { "image": null }
    @GetMapping("/{userId}/photo")
    public ResponseEntity<?> getPhoto(@PathVariable Long userId) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            return ResponseEntity.notFound().build();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("image", user.getProfileImage());
        return ResponseEntity.ok(body);
    }

    public static class PhotoRequest {
        public String image; // "data:image/jpeg;base64,...."
    }

    // PUT http://localhost:8080/api/users/5/photo
    @PutMapping("/{userId}/photo")
    public ResponseEntity<?> uploadPhoto(@PathVariable Long userId, @RequestBody PhotoRequest req) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            return ResponseEntity.notFound().build();
        }
        String image = req == null ? null : req.image;
        if (image == null || image.isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Please choose a photo.");
        }

        // Only JPG, PNG or WEBP images are accepted
        String[] allowed = { "data:image/jpeg;base64,", "data:image/png;base64,", "data:image/webp;base64," };
        String prefix = null;
        for (String a : allowed) {
            if (image.startsWith(a)) prefix = a;
        }
        if (prefix == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Photo must be a JPG, PNG or WEBP image.");
        }

        // Make sure it's real image data and not too big
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(image.substring(prefix.length()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("That photo couldn't be read. Try another one.");
        }
        if (bytes.length == 0) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("That photo is empty.");
        }
        if (bytes.length > MAX_PHOTO_BYTES) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Photo is too large. Please use one under 1.5 MB.");
        }

        user.setProfileImage(image);
        userRepository.save(user);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("image", image);
        return ResponseEntity.ok(body);
    }

    // DELETE http://localhost:8080/api/users/5/photo  - back to initials
    @DeleteMapping("/{userId}/photo")
    public ResponseEntity<?> removePhoto(@PathVariable Long userId) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            return ResponseEntity.notFound().build();
        }
        user.setProfileImage(null);
        userRepository.save(user);
        return ResponseEntity.ok().build();
    }
}