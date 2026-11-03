package com.shank.payment_service.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "payments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private Long bookingId;
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentMethod paymentMethod;
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 32)
    private PaymentStatus paymentStatus;
    @Column(nullable = false, unique = true, length = 36)
    private String transactionId;
    @Column(nullable = false)
    private LocalDateTime paymentDate;
    private Boolean notificationPending;

    public Payment(Long bookingId, BigDecimal amount, PaymentMethod paymentMethod) {
        this.bookingId = bookingId;
        this.amount = amount;
        this.paymentMethod = paymentMethod;
        this.paymentStatus = PaymentStatus.PENDING;
        this.transactionId = UUID.randomUUID().toString();
        this.paymentDate = LocalDateTime.now();
    }

    public void complete(boolean success) {
        this.paymentStatus = success ? PaymentStatus.SUCCESS : PaymentStatus.FAILED;
        this.notificationPending = true;
    }

    public void acknowledgeNotification() { this.notificationPending = false; }
}