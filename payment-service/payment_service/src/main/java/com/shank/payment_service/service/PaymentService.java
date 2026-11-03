package com.shank.payment_service.service;

import java.util.List;
import com.shank.payment_service.dto.PaymentRequest;
import com.shank.payment_service.dto.PaymentResponse;
import com.shank.payment_service.entity.Payment;
import com.shank.payment_service.exception.PaymentNotFoundException;
import com.shank.payment_service.repository.PaymentRepository;
import org.springframework.stereotype.Service;
import com.shank.payment_service.entity.PaymentStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class PaymentService {
    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private final PaymentRepository repository;
    private final BookingServiceClient bookingClient;
    private final boolean simulatedSuccess;

    public PaymentService(PaymentRepository repository, BookingServiceClient bookingClient,
                          @Value("${payments.simulation.success:true}") boolean simulatedSuccess) {
        this.repository = repository;
        this.bookingClient = bookingClient;
        this.simulatedSuccess = simulatedSuccess;
    }

    public synchronized PaymentResponse create(PaymentRequest request) {
        boolean alreadyPaid = repository.findByBookingId(request.bookingId()).stream()
                .anyMatch(payment -> payment.getPaymentStatus() != PaymentStatus.FAILED);
        if (alreadyPaid) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Booking already has a payment attempt; check its status");
        }
        var booking = bookingClient.findById(request.bookingId());
        if (!java.util.Set.of("PENDING", "PENDING_PAYMENT").contains(booking.bookingStatus())
                || (booking.journeyDate() != null && booking.journeyDate().isBefore(java.time.LocalDate.now()))
                || ("PENDING_PAYMENT".equals(booking.bookingStatus()) && (booking.reservationExpiryTime() == null
                    || !booking.reservationExpiryTime().isAfter(java.time.Instant.now())))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Booking is not eligible for payment");
        }
        Payment payment = repository.saveAndFlush(new Payment(
                request.bookingId(), booking.totalAmount(), request.paymentMethod()));
        payment.complete(simulatedSuccess);
        payment = repository.saveAndFlush(payment);
        log.info("Simulated payment id={} bookingId={} status={}", payment.getId(), payment.getBookingId(), payment.getPaymentStatus());
        notifyBooking(payment);
        return PaymentResponse.from(payment);
    }

    public PaymentResponse retryConfirmation(Long id) {
        Payment payment = repository.findById(id).orElseThrow(() -> new PaymentNotFoundException(id));
        if (payment.getPaymentStatus() != PaymentStatus.SUCCESS) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only successful payments can confirm a booking");
        }
        notifyBooking(payment);
        return PaymentResponse.from(payment);
    }

    public PaymentResponse retryNotification(Long id) {
        Payment payment = repository.findById(id).orElseThrow(() -> new PaymentNotFoundException(id));
        if (payment.getPaymentStatus() == PaymentStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Payment outcome is not available");
        }
        notifyBooking(payment);
        return PaymentResponse.from(payment);
    }

    private void notifyBooking(Payment payment) {
        if (payment.getPaymentStatus() == PaymentStatus.SUCCESS) { bookingClient.confirm(payment.getBookingId()); }
        else { bookingClient.paymentFailed(payment.getBookingId()); }
        payment.acknowledgeNotification();
        repository.saveAndFlush(payment);
    }

    public List<PaymentResponse> findAll() {
        return repository.findAll().stream().map(PaymentResponse::from).toList();
    }

    public PaymentResponse findById(Long id) {
        return PaymentResponse.from(repository.findById(id)
                .orElseThrow(() -> new PaymentNotFoundException(id)));
    }

    public List<PaymentResponse> findByBookingId(Long bookingId) {
        return repository.findByBookingId(bookingId).stream().map(PaymentResponse::from).toList();
    }
}