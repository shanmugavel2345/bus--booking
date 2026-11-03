package com.shank.seat_management_service.repository;

import com.shank.seat_management_service.entity.Seat;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SeatRepository extends JpaRepository<Seat, Long> {
    List<Seat> findByBusIdAndJourneyDateOrderById(Long busId, LocalDate journeyDate);
    List<Seat> findByBookingIdOrderById(Long bookingId);
}