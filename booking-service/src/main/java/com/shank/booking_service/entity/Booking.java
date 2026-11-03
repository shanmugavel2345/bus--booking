package com.shank.booking_service.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "booking")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Booking {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private Long busId;
    @Column(nullable = false)
    private String passengerName;
    @Column(nullable = false)
    private String passengerEmail;
    @Column(nullable = false)
    private String passengerPhone;
    @Column(nullable = false)
    private LocalDate journeyDate;
    @Column(nullable = false)
    private Integer numberOfSeats;
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal totalAmount;
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 32)
    private BookingStatus bookingStatus;
    @Column(length = 10000)
    private String selectedSeats;
    private String reservationId;
    private java.time.Instant reservationExpiryTime;
    private String pendingSeatAction;
    private java.time.Instant createdAt;

    public Booking(Long busId, String passengerName, String passengerEmail,
                   String passengerPhone, LocalDate journeyDate, Integer numberOfSeats,
                   BigDecimal totalAmount) {
        this.busId = busId;
        this.passengerName = passengerName;
        this.passengerEmail = passengerEmail;
        this.passengerPhone = passengerPhone;
        this.journeyDate = journeyDate;
        this.numberOfSeats = numberOfSeats;
        this.totalAmount = totalAmount;
        this.bookingStatus = BookingStatus.PENDING;
    }

    public void cancel() {
        this.bookingStatus = BookingStatus.CANCELLED;
    }

    public void confirm() {
        this.bookingStatus = BookingStatus.CONFIRMED;
    }

    public void prepareSeats(java.util.List<String> seatNumbers) {
        this.selectedSeats = seatNumbers == null ? "" : String.join(",", seatNumbers);
        this.createdAt = java.time.Instant.now();
        this.bookingStatus = BookingStatus.RESERVING;
    }
    public java.util.List<String> selectedSeatNumbers() {
        return selectedSeats == null || selectedSeats.isEmpty() ? java.util.List.of() : java.util.List.of(selectedSeats.split(","));
    }
    public void reserved(String reference, java.time.Instant expiry, java.util.List<String> numbers) {
        this.reservationId = reference;
        this.reservationExpiryTime = expiry;
        this.selectedSeats = String.join(",", numbers);
        this.bookingStatus = BookingStatus.PENDING_PAYMENT;
    }
    public void transition(BookingStatus status, String seatAction) {
        this.bookingStatus = status;
        this.pendingSeatAction = seatAction;
    }
}