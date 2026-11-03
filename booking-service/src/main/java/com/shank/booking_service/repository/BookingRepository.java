package com.shank.booking_service.repository;

import com.shank.booking_service.entity.Booking;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BookingRepository extends JpaRepository<Booking, Long> {
	java.util.List<Booking> findByPendingSeatActionIsNotNull();
	java.util.List<Booking> findByBookingStatusAndCreatedAtBefore(
		com.shank.booking_service.entity.BookingStatus status, java.time.Instant cutoff);
	@org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
	@org.springframework.data.jpa.repository.Query("select booking from Booking booking where booking.id = :id")
	java.util.Optional<Booking> findForUpdate(@org.springframework.data.repository.query.Param("id") Long id);
}