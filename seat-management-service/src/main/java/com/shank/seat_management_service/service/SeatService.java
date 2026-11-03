package com.shank.seat_management_service.service;

import com.shank.seat_management_service.dto.SeatContracts.*;
import com.shank.seat_management_service.entity.*;
import com.shank.seat_management_service.entity.SeatReservation.State;
import com.shank.seat_management_service.exception.SeatException;
import com.shank.seat_management_service.repository.*;
import java.time.*;
import java.util.*;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class SeatService {
    private static final Logger log = LoggerFactory.getLogger(SeatService.class);
    private final SeatRepository seats;
    private final JourneyInventoryRepository inventories;
    private final ReservationRepository reservations;
    private final BusServiceClient busClient;
    private final Clock clock;
    private final Duration duration;
    private final String prefix;

    public SeatService(SeatRepository seats, JourneyInventoryRepository inventories, ReservationRepository reservations,
            BusServiceClient busClient, Clock clock, @Value("${seats.reservation-duration:PT10M}") Duration duration,
            @Value("${seats.number-prefix:S}") String prefix) {
        if (duration.isZero() || duration.isNegative() || !prefix.matches("[A-Za-z0-9-]{0,20}")) {
            throw new IllegalArgumentException("Invalid reservation duration or seat prefix");
        }
        this.seats = seats;
        this.inventories = inventories;
        this.reservations = reservations;
        this.busClient = busClient;
        this.clock = clock;
        this.duration = duration;
        this.prefix = prefix;
    }

    public List<SeatResponse> initialize(InitializeRequest request) {
        var bus = busClient.findById(request.busId());
        String key = JourneyInventory.key(request.busId(), request.journeyDate());
        if (inventories.existsById(key)) { throw conflict("Inventory already initialized"); }
        inventories.saveAndFlush(new JourneyInventory(request.busId(), request.journeyDate()));
        List<Seat> generated = new ArrayList<>();
        for (int number = 1; number <= bus.totalSeates(); number++) {
            generated.add(new Seat(request.busId(), request.journeyDate(), prefix + number, clock.instant()));
        }
        return seats.saveAll(generated).stream().map(SeatResponse::from).toList();
    }

    public List<SeatResponse> list(Long busId, LocalDate date, boolean availableOnly) {
        lock(busId, date);
        expireJourney(busId, date);
        return seats.findByBusIdAndJourneyDateOrderById(busId, date).stream()
                .filter(seat -> !availableOnly || seat.getSeatStatus() == SeatStatus.AVAILABLE)
                .map(SeatResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public SeatResponse findById(Long id) {
        return SeatResponse.from(seats.findById(id).orElseThrow(() -> missing("Seat not found")));
    }

    @Transactional(readOnly = true)
    public List<SeatResponse> forBooking(Long bookingId) {
        return seats.findByBookingIdOrderById(bookingId).stream().map(SeatResponse::from).toList();
    }

    public ReservationResponse reserve(ReserveRequest request) {
        lock(request.busId(), request.journeyDate());
        expireJourney(request.busId(), request.journeyDate());
        if (!request.isSelectionValid()) { throw conflict("Invalid seat count"); }
        var existing = reservations.findById(request.bookingId());
        var inventory = seats.findByBusIdAndJourneyDateOrderById(request.busId(), request.journeyDate());
        boolean automatic = request.seatNumbers() == null || request.seatNumbers().isEmpty();
        List<String> numbers;
        if (automatic && existing.isPresent()) {
            numbers = List.of(existing.get().getSelection().split(","));
            if (numbers.size() != request.numberOfSeats()) { throw conflict("Reservation count mismatch"); }
        } else if (automatic) {
            numbers = inventory.stream().filter(seat -> seat.getSeatStatus() == SeatStatus.AVAILABLE)
                    .limit(request.numberOfSeats()).map(Seat::getSeatNumber).sorted().toList();
            if (numbers.size() != request.numberOfSeats()) { throw conflict("Insufficient available seats"); }
        } else { numbers = request.seatNumbers().stream().sorted().toList(); }
        if (new HashSet<>(numbers).size() != numbers.size()) { throw conflict("Duplicate seat selection"); }
        String selection = String.join(",", numbers);
        if (existing.isPresent()) {
            var reservation = existing.get();
            if (!reservation.getBusId().equals(request.busId()) || !reservation.getJourneyDate().equals(request.journeyDate())
                    || !reservation.getSelection().equals(selection) || reservation.getState() != State.RESERVED) {
                throw conflict("Booking reservation cannot be changed or reused");
            }
            return response(reservation);
        }
        var selected = inventory.stream().filter(seat -> numbers.contains(seat.getSeatNumber())).toList();
        if (selected.size() != numbers.size()) { throw missing("Invalid seat selection"); }
        if (selected.stream().anyMatch(seat -> seat.getSeatStatus() != SeatStatus.AVAILABLE)) {
            throw conflict("One or more seats are unavailable");
        }
        Instant now = clock.instant();
        var reservation = reservations.saveAndFlush(new SeatReservation(request.bookingId(), request.busId(),
                request.journeyDate(), selection, now.plus(duration)));
        selected.forEach(seat -> seat.reserve(request.bookingId(), reservation.getExpiresAt(), now));
        seats.flush();
        return response(reservation);
    }

    public ReservationResponse change(Long bookingId, String reference, String action) {
        var location = reservations.location(bookingId).orElseThrow(() -> missing("Reservation not found"));
        lock(location.busId(), location.journeyDate());
        expireJourney(location.busId(), location.journeyDate());
        var reservation = reservations.findById(bookingId).orElseThrow();
        if (reference != null && !reference.equals(reservation.getReservationId())) { throw conflict("Reservation reference mismatch"); }
        var selected = seats.findByBookingIdOrderById(bookingId);
        State state = reservation.getState();
        if ("confirm".equals(action)) {
            if (state == State.BOOKED) { return response(reservation); }
            if (state != State.RESERVED || !reservation.getExpiresAt().isAfter(clock.instant())) {
                throw conflict("Reservation is no longer active");
            }
            selected.forEach(seat -> seat.confirm(clock.instant()));
            reservation.transition(State.BOOKED);
        } else if ("release".equals(action)) {
            if (state == State.BOOKED) { throw conflict("Booked seats require cancellation"); }
            if (state == State.RESERVED) {
                selected.forEach(seat -> seat.release(clock.instant()));
                reservation.transition(State.RELEASED);
            }
        } else if ("cancel".equals(action)) {
            selected.forEach(seat -> seat.release(clock.instant()));
            reservation.transition(State.CANCELLED);
        } else { throw new IllegalArgumentException("Unknown seat action"); }
        seats.flush();
        return response(reservation);
    }

    public void expire(Long bookingId) {
        var location = reservations.location(bookingId);
        if (location.isPresent()) {
            lock(location.get().busId(), location.get().journeyDate());
            expireJourney(location.get().busId(), location.get().journeyDate());
        }
    }

    private void expireJourney(Long busId, LocalDate date) {
        Instant now = clock.instant();
        var inventory = seats.findByBusIdAndJourneyDateOrderById(busId, date);
        Set<Long> expired = new HashSet<>();
        inventory.stream().filter(seat -> seat.getSeatStatus() == SeatStatus.RESERVED
                && !seat.getReservationExpiryTime().isAfter(now)).forEach(seat -> {
                    expired.add(seat.getBookingId());
                    seat.release(now);
                });
        expired.forEach(id -> {
            reservations.findById(id).ifPresent(reservation -> reservation.transition(State.EXPIRED));
            log.info("Expired seat reservation bookingId={}", id);
        });
    }

    private void lock(Long busId, LocalDate date) {
        inventories.lock(JourneyInventory.key(busId, date)).orElseThrow(() -> missing("Initialize inventory for this bus and journey date first"));
    }
    private ReservationResponse response(SeatReservation reservation) {
        return new ReservationResponse(reservation.getBookingId(), reservation.getReservationId(), reservation.getState().name(),
                reservation.getExpiresAt(), forBooking(reservation.getBookingId()));
    }
    private SeatException missing(String message) { return new SeatException(HttpStatus.NOT_FOUND, message); }
    private SeatException conflict(String message) { return new SeatException(HttpStatus.CONFLICT, message); }
}