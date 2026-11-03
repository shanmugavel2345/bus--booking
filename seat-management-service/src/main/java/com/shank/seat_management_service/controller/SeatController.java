package com.shank.seat_management_service.controller;

import com.shank.seat_management_service.dto.SeatContracts.*;
import com.shank.seat_management_service.service.SeatService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/seats")
public class SeatController {
    private final SeatService service;
    public SeatController(SeatService service) { this.service = service; }
    @PostMapping("/initialize")
    public ResponseEntity<List<SeatResponse>> initialize(@Valid @RequestBody InitializeRequest request) {
        return ResponseEntity.status(201).body(service.initialize(request));
    }
    @GetMapping("/bus/{busId}")
    public ResponseEntity<List<SeatResponse>> all(@PathVariable @Positive Long busId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate journeyDate) {
        return ResponseEntity.ok(service.list(busId, journeyDate, false));
    }
    @GetMapping("/bus/{busId}/available")
    public ResponseEntity<List<SeatResponse>> available(@PathVariable @Positive Long busId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate journeyDate) {
        return ResponseEntity.ok(service.list(busId, journeyDate, true));
    }
    @GetMapping("/{seatId}")
    public ResponseEntity<SeatResponse> detail(@PathVariable @Positive Long seatId) { return ResponseEntity.ok(service.findById(seatId)); }
    @GetMapping("/booking/{bookingId}")
    public ResponseEntity<List<SeatResponse>> booking(@PathVariable @Positive Long bookingId) { return ResponseEntity.ok(service.forBooking(bookingId)); }
    @PostMapping("/reserve")
    public ResponseEntity<ReservationResponse> reserve(@Valid @RequestBody ReserveRequest request) { return ResponseEntity.ok(service.reserve(request)); }
    @PostMapping("/confirm/{bookingId}")
    public ResponseEntity<ReservationResponse> confirm(@PathVariable @Positive Long bookingId,
            @RequestHeader(value = "X-Reservation-Id", required = false) String reference) {
        return ResponseEntity.ok(service.change(bookingId, reference, "confirm"));
    }
    @PostMapping("/release/{bookingId}")
    public ResponseEntity<ReservationResponse> release(@PathVariable @Positive Long bookingId,
            @RequestHeader(value = "X-Reservation-Id", required = false) String reference) {
        return ResponseEntity.ok(service.change(bookingId, reference, "release"));
    }
    @PostMapping("/cancel/{bookingId}")
    public ResponseEntity<ReservationResponse> cancel(@PathVariable @Positive Long bookingId,
            @RequestHeader(value = "X-Reservation-Id", required = false) String reference) {
        return ResponseEntity.ok(service.change(bookingId, reference, "cancel"));
    }
}