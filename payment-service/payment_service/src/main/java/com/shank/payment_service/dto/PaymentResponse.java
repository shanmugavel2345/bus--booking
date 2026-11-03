package com.shank.payment_service.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import com.shank.payment_service.entity.Payment;
import com.shank.payment_service.entity.PaymentMethod;
import com.shank.payment_service.entity.PaymentStatus;

public record PaymentResponse(Long id, Long bookingId, BigDecimal amount,
                              PaymentMethod paymentMethod, PaymentStatus paymentStatus,
                              String transactionId, LocalDateTime paymentDate) {
    public static PaymentResponse from(Payment payment) {
        return new PaymentResponse(payment.getId(), payment.getBookingId(), payment.getAmount(),
                payment.getPaymentMethod(), payment.getPaymentStatus(),
                payment.getTransactionId(), payment.getPaymentDate());
    }
}