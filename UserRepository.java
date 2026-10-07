package com.zentramart.backend.account.repository;

import com.zentramart.backend.account.model.User;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);

    // Case-insensitive versions, so "Kasun@Gmail.com" and "kasun@gmail.com" are the same account
    Optional<User> findByEmailIgnoreCase(String email);
    boolean existsByEmailIgnoreCase(String email);
}