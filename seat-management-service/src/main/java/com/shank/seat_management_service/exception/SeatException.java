package com.shank.seat_management_service.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public class SeatException extends ResponseStatusException {
    public SeatException(HttpStatus status, String message) { super(status, message); }
}