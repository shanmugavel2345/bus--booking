package com.shank.booking_service.controller;

import java.util.List;
import com.shank.booking_service.dto.BookingRequest;
import com.shank.booking_service.dto.BookingResponse;
import com.shank.booking_service.service.BookingService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping({"/api/booking", "/api/bookings"})
public class BookingController {
    private final BookingService service;

    public BookingController(BookingService service) {
        this.service = service;
    }

    @PostMapping({"", "/createBooking"})
    @ResponseStatus(HttpStatus.CREATED)
    public BookingResponse create(@Valid @RequestBody BookingRequest request) {
        return service.create(request);
    }

    @GetMapping({"", "/getAllBookings"})
    public List<BookingResponse> findAll() {
        return service.findAll();
    }

    @GetMapping("/{id}")
    public BookingResponse findById(@PathVariable Long id) {
        return service.findById(id);
    }

    @PutMapping("/{id}/cancel")
    public BookingResponse cancel(@PathVariable Long id) {
        return service.cancel(id);
    }
}