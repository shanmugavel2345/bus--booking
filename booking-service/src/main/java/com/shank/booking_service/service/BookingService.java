package com.shank.booking_service.service;

import java.math.BigDecimal;
import java.util.List;
import com.shank.booking_service.dto.BookingRequest;
import com.shank.booking_service.dto.BookingResponse;
import com.shank.booking_service.entity.Booking;
import com.shank.booking_service.exception.BookingNotFoundException;
import com.shank.booking_service.repository.BookingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import com.shank.booking_service.entity.BookingStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
public class BookingService {
    private static final Logger log = LoggerFactory.getLogger(BookingService.class);
    private final BookingRepository repository;
    private final BusServiceClient busClient;
    private final SeatServiceClient seatClient;
    private final TransactionTemplate transactions;

    public BookingService(BookingRepository repository, BusServiceClient busClient, SeatServiceClient seatClient,
                          PlatformTransactionManager manager) {
        this.repository = repository;
        this.busClient = busClient;
        this.seatClient = seatClient;
        this.transactions = new TransactionTemplate(manager);
    }

    public BookingResponse create(BookingRequest request) {
        BigDecimal total = busClient.fareFor(request.busId())
            .multiply(BigDecimal.valueOf(request.numberOfSeats()));
        if (total.compareTo(new BigDecimal("99999999999999999.99")) > 0) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "Booking amount exceeds supported limit");
        }
        Booking draft = transactions.execute(status -> {
            var booking = new Booking(request.busId(), request.passengerName(), request.passengerEmail(),
                    request.passengerPhone(), request.journeyDate(), request.numberOfSeats(), total);
            booking.prepareSeats(request.seatNumbers());
            return repository.saveAndFlush(booking);
        });
        try {
            var reservation = seatClient.reserve(draft);
            return transactions.execute(status -> {
                var booking = locked(draft.getId());
                if (booking.getBookingStatus() != BookingStatus.RESERVING) { throw conflict("Booking reservation was interrupted"); }
                booking.reserved(reservation.reservationId(), reservation.reservationExpiryTime(),
                        reservation.seats().stream().map(SeatServiceClient.Seat::seatNumber).toList());
                return BookingResponse.from(repository.saveAndFlush(booking));
            });
        } catch (RuntimeException failure) {
            try { cancel(draft.getId()); }
            catch (RuntimeException compensation) { log.warn("Seat compensation queued bookingId={}", draft.getId()); }
            throw failure;
        }
    }

    public List<BookingResponse> findAll() {
        return repository.findAll().stream().map(BookingResponse::from).toList();
    }

    public BookingResponse findById(Long id) {
        return BookingResponse.from(requireBooking(id));
    }

    public BookingResponse cancel(Long id) {
        transactions.executeWithoutResult(status -> {
            var booking = locked(id);
            if (booking.getBookingStatus() != BookingStatus.CANCELLED) {
                booking.transition(BookingStatus.CANCEL_PENDING, "cancel");
            }
        });
        return processPending(id);
    }

    public BookingResponse confirm(Long id) {
        transactions.executeWithoutResult(status -> {
            var booking = locked(id);
            if (booking.getBookingStatus() == BookingStatus.CONFIRMED) { return; }
            if (!java.util.Set.of(BookingStatus.PENDING, BookingStatus.PENDING_PAYMENT, BookingStatus.CONFIRMING).contains(booking.getBookingStatus())) {
                throw conflict("Booking cannot be confirmed in its current state");
            }
            booking.transition(BookingStatus.CONFIRMING, "confirm");
        });
        return processPending(id);
    }

    public BookingResponse paymentFailed(Long id) {
        transactions.executeWithoutResult(status -> {
            var booking = locked(id);
            if (java.util.Set.of(BookingStatus.CONFIRMED, BookingStatus.CONFIRMING).contains(booking.getBookingStatus())) {
                throw conflict("Successful payment notification cannot be reversed");
            }
            if (java.util.Set.of(BookingStatus.CANCELLED, BookingStatus.CANCEL_PENDING).contains(booking.getBookingStatus())) { return; }
            booking.transition(BookingStatus.PAYMENT_FAILED, "release");
        });
        return processPending(id);
    }

    public BookingResponse processPending(Long id) {
        Booking snapshot = requireBooking(id);
        String action = snapshot.getPendingSeatAction();
        if (action == null) { return BookingResponse.from(snapshot); }
        if (snapshot.getSelectedSeats() != null) {
            try { seatClient.change(snapshot, action); }
            catch (ResponseStatusException failure) {
                if ("confirm".equals(action) && failure.getStatusCode().value() == 409) {
                    transactions.executeWithoutResult(status -> {
                        var current = locked(id);
                        if ("confirm".equals(current.getPendingSeatAction())) { current.transition(BookingStatus.EXPIRED, null); }
                    });
                }
                throw failure;
            }
        }
        return transactions.execute(status -> {
            var current = locked(id);
            if (!java.util.Objects.equals(action, current.getPendingSeatAction())) { throw conflict("Booking changed during seat notification"); }
            BookingStatus target = switch (action) {
                case "confirm" -> BookingStatus.CONFIRMED;
                case "cancel" -> BookingStatus.CANCELLED;
                default -> BookingStatus.PAYMENT_FAILED;
            };
            current.transition(target, null);
            log.info("Booking seat action completed bookingId={} action={}", id, action);
            return BookingResponse.from(repository.saveAndFlush(current));
        });
    }

    private Booking locked(Long id) { return repository.findForUpdate(id).orElseThrow(() -> new BookingNotFoundException(id)); }
    private ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT, message); }
    private Booking requireBooking(Long id) {
        return repository.findById(id).orElseThrow(() -> new BookingNotFoundException(id));
    }
}