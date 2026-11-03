package com.shank.booking_service.controller;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import com.shank.booking_service.dto.BookingResponse;
import com.shank.booking_service.service.BookingService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/internal/bookings")
public class InternalBookingController {
    private final BookingService service;
    private final String internalToken;

    public InternalBookingController(BookingService service, @Value("${services.internal-token}") String internalToken) {
        if (internalToken.isBlank()) {
            throw new IllegalArgumentException("Internal service token must not be blank");
        }
        this.service = service;
        this.internalToken = internalToken;
    }

    @PutMapping("/{id}/confirm")
    public BookingResponse confirm(@PathVariable Long id,
            @RequestHeader(value = "X-Internal-Token", defaultValue = "") String token) {
        authenticate(token);
        return service.confirm(id);
    }
    @PutMapping("/{id}/payment-failed")
    public BookingResponse paymentFailed(@PathVariable Long id,
            @RequestHeader(value = "X-Internal-Token", defaultValue = "") String token) {
        authenticate(token);
        return service.paymentFailed(id);
    }
    private void authenticate(String token) {
        if (!MessageDigest.isEqual(internalToken.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Internal service authentication required");
        }
    }
}