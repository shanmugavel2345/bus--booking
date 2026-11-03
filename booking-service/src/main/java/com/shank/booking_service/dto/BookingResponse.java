package com.shank.booking_service.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import com.shank.booking_service.entity.Booking;
import com.shank.booking_service.entity.BookingStatus;

public record BookingResponse(Long id, Long busId, String passengerName,
        String passengerEmail, String passengerPhone, LocalDate journeyDate,
        Integer numberOfSeats, BigDecimal totalAmount, BookingStatus bookingStatus,
        java.util.List<String> seatNumbers, String reservationId, java.time.Instant reservationExpiryTime) {
    public static BookingResponse from(Booking booking) {
        return new BookingResponse(booking.getId(), booking.getBusId(), booking.getPassengerName(),
                booking.getPassengerEmail(), booking.getPassengerPhone(), booking.getJourneyDate(),
                booking.getNumberOfSeats(), booking.getTotalAmount(), booking.getBookingStatus(),
                booking.selectedSeatNumbers(), booking.getReservationId(), booking.getReservationExpiryTime());
    }
}