package com.shank.seat_management_service.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import lombok.*;

@Entity
@Table(name = "seat_reservations", indexes = @Index(columnList = "state,expiresAt"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SeatReservation {
    public enum State { RESERVED, BOOKED, RELEASED, CANCELLED, EXPIRED }
    @Id
    private Long bookingId;
    @Column(nullable = false, unique = true)
    private String reservationId;
    @Column(nullable = false)
    private Long busId;
    @Column(nullable = false)
    private LocalDate journeyDate;
    @Column(nullable = false, length = 10000)
    private String selection;
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private State state;
    @Column(nullable = false)
    private Instant expiresAt;

    public SeatReservation(Long bookingId, Long busId, LocalDate date, String selection, Instant expiry) {
        this.bookingId = bookingId;
        this.reservationId = java.util.UUID.randomUUID().toString();
        this.busId = busId;
        this.journeyDate = date;
        this.selection = selection;
        this.expiresAt = expiry;
        this.state = State.RESERVED;
    }

    public void transition(State state) { this.state = state; }
}