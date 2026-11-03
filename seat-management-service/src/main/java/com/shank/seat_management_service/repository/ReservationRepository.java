package com.shank.seat_management_service.repository;

import com.shank.seat_management_service.entity.SeatReservation;
import com.shank.seat_management_service.dto.SeatContracts.ReservationLocation;
import java.time.Instant;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface ReservationRepository extends JpaRepository<SeatReservation, Long> {
    @Query("select new com.shank.seat_management_service.dto.SeatContracts$ReservationLocation(r.busId, r.journeyDate) from SeatReservation r where r.bookingId = :id")
    Optional<ReservationLocation> location(@Param("id") Long id);

    @Query("select r.bookingId from SeatReservation r where r.state = com.shank.seat_management_service.entity.SeatReservation$State.RESERVED and r.expiresAt <= :now")
    List<Long> expired(@Param("now") Instant now);
}