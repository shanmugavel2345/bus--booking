package com.shank.payment_service.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record BookingResponse(Long id, Long busId, String passengerName, String passengerEmail,
                              String passengerPhone, LocalDate journeyDate, Integer numberOfSeats,
                              BigDecimal totalAmount, String bookingStatus, java.time.Instant reservationExpiryTime) {
    public BookingResponse(Long id, Long busId, String passengerName, String passengerEmail, String passengerPhone,
                           LocalDate journeyDate, Integer numberOfSeats, BigDecimal totalAmount, String bookingStatus) {
        this(id, busId, passengerName, passengerEmail, passengerPhone, journeyDate, numberOfSeats, totalAmount, bookingStatus, null);
    }
}