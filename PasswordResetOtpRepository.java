package com.zentramart.backend.account.repository;

import com.zentramart.backend.account.model.PasswordResetOtp;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface PasswordResetOtpRepository extends JpaRepository<PasswordResetOtp, Long> {
    // The newest request for this user
    Optional<PasswordResetOtp> findTopByUserIdOrderByOtpIdDesc(Long userId);
}