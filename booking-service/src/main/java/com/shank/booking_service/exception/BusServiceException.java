package com.shank.booking_service.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public class BusServiceException extends ResponseStatusException {
    public BusServiceException(HttpStatus status, String message) {
        super(status, message);
    }
}