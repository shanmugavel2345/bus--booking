package com.shank.payment_service.controller;

import java.net.URI;
import java.util.List;
import com.shank.payment_service.dto.PaymentRequest;
import com.shank.payment_service.dto.PaymentResponse;
import com.shank.payment_service.service.PaymentService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {
    private final PaymentService service;

    public PaymentController(PaymentService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<PaymentResponse> create(@Valid @RequestBody PaymentRequest request) {
        PaymentResponse response = service.create(request);
        return ResponseEntity.created(URI.create("/api/payments/" + response.id())).body(response);
    }

    @GetMapping
    public List<PaymentResponse> findAll() {
        return service.findAll();
    }

    @PostMapping("/{id}/confirm-booking")
    public PaymentResponse retryConfirmation(@PathVariable Long id) {
        return service.retryConfirmation(id);
    }

    @GetMapping("/{id}")
    public PaymentResponse findById(@PathVariable Long id) {
        return service.findById(id);
    }

    @PostMapping("/{id}/notify-booking")
    public PaymentResponse retryNotification(@PathVariable Long id) {
        return service.retryNotification(id);
    }

    @GetMapping("/booking/{bookingId}")
    public List<PaymentResponse> findByBookingId(@PathVariable Long bookingId) {
        return service.findByBookingId(bookingId);
    }
}