package com.shank.seat_management_service.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import lombok.*;

@Entity
@Table(name = "seats", uniqueConstraints = @UniqueConstraint(columnNames = {"busId", "journeyDate", "seatNumber"}),
        indexes = {@Index(columnList = "busId,journeyDate,seatStatus"), @Index(columnList = "bookingId"),
                @Index(columnList = "seatStatus,reservationExpiryTime")})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Seat {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private Long busId;
    @Column(nullable = false)
    private LocalDate journeyDate;
    @Column(nullable = false, length = 32)
    private String seatNumber;
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private SeatStatus seatStatus;
    private Long bookingId;
    private Instant reservationExpiryTime;
    @Column(nullable = false, updatable = false)
    private Instant createdAt;
    @Column(nullable = false)
    private Instant updatedAt;

    public Seat(Long busId, LocalDate journeyDate, String seatNumber, Instant now) {
        this.busId = busId;
        this.journeyDate = journeyDate;
        this.seatNumber = seatNumber;
        this.seatStatus = SeatStatus.AVAILABLE;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void reserve(Long bookingId, Instant expiry, Instant now) {
        this.bookingId = bookingId;
        this.reservationExpiryTime = expiry;
        this.seatStatus = SeatStatus.RESERVED;
        this.updatedAt = now;
    }

    public void confirm(Instant now) {
        this.seatStatus = SeatStatus.BOOKED;
        this.reservationExpiryTime = null;
        this.updatedAt = now;
    }

    public void release(Instant now) {
        this.seatStatus = SeatStatus.AVAILABLE;
        this.bookingId = null;
        this.reservationExpiryTime = null;
        this.updatedAt = now;
    }
}