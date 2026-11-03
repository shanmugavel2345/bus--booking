package com.shank.booking_service.exception;

public class BookingNotFoundException extends RuntimeException {
    public BookingNotFoundException(Long id) {
        super("Booking not found with id: " + id);
    }
}