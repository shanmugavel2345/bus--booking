package com.shank.payment_service.repository;

import java.util.List;
import com.shank.payment_service.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, Long> {
    List<Payment> findByNotificationPendingTrue();
    List<Payment> findByBookingId(Long bookingId);
}