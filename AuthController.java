package com.zentramart.backend.account.controller;

import com.zentramart.backend.account.dto.LoginRequest;
import com.zentramart.backend.account.dto.RegisterRequest;
import com.zentramart.backend.account.model.User;
import com.zentramart.backend.account.repository.UserRepository;
import com.zentramart.backend.common.util.Validation;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@CrossOrigin(origins = "*")
public class AuthController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Autowired
    public AuthController(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    // POST http://localhost:8080/api/auth/register
    // PBI-01: create account | PBI-02: choose role at sign-up
    // Every rule is checked here with a clear message, so the sign-up form
    // can show exactly what's wrong.
    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody RegisterRequest request) {

        String problem = Validation.checkFullName(request.getFullName());
        if (problem == null) problem = Validation.checkEmail(request.getEmail());
        if (problem == null) problem = Validation.checkPassword(request.getPassword());
        if (problem != null) return ResponseEntity.badRequest().body(problem);

        String email = request.getEmail().trim().toLowerCase();
        if (userRepository.existsByEmailIgnoreCase(email)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body("An account with this email already exists. Try logging in instead.");
        }

        String roleInput = request.getRole() == null ? "" : request.getRole().trim().toUpperCase();
        if (!roleInput.equals("BUYER") && !roleInput.equals("SELLER")) {
            return ResponseEntity.badRequest().body("Role must be BUYER or SELLER.");
        }

        User user = new User();
        user.setFullName(request.getFullName().trim().replaceAll("\\s+", " "));
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(request.getPassword())); // never store plain text
        user.setRole(User.Role.valueOf(roleInput));
        user.setStatus(User.Status.ACTIVE);

        User saved = userRepository.save(user);

        // Never send the password hash back to the client
        saved.setPasswordHash(null);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    // POST http://localhost:8080/api/auth/login
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request) {

        if (request.getEmail() == null || request.getEmail().isBlank()
                || request.getPassword() == null || request.getPassword().isEmpty()) {
            return ResponseEntity.badRequest().body("Enter your email and password.");
        }

        User user = userRepository.findByEmailIgnoreCase(request.getEmail().trim()).orElse(null);

        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body("Invalid email or password.");
        }

        if (user.getStatus() != User.Status.ACTIVE) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body("This account is suspended or deactivated.");
        }

        user.setPasswordHash(null); // don't leak the hash
        return ResponseEntity.ok(user);
    }
}