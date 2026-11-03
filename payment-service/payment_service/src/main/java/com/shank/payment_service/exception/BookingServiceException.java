package com.shank.payment_service.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public class BookingServiceException extends ResponseStatusException {
    public BookingServiceException(HttpStatus status, String message) {
        super(status, message);
    }
}