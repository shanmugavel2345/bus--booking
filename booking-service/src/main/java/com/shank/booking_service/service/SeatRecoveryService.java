package com.shank.booking_service.service;

import com.shank.booking_service.entity.BookingStatus;
import com.shank.booking_service.repository.BookingRepository;
import java.time.Instant;
import org.slf4j.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
@ConditionalOnProperty(name = "booking.seat-recovery-enabled", havingValue = "true", matchIfMissing = true)
public class SeatRecoveryService {
    private static final Logger log = LoggerFactory.getLogger(SeatRecoveryService.class);
    private final BookingRepository repository;
    private final BookingService service;
    public SeatRecoveryService(BookingRepository repository, BookingService service) { this.repository = repository; this.service = service; }
    @Scheduled(fixedDelayString = "${booking.seat-recovery-delay-ms:30000}")
    public void retry() {
        repository.findByPendingSeatActionIsNotNull().forEach(booking -> {
            try { service.processPending(booking.getId()); }
            catch (RuntimeException failure) { log.warn("Seat action retry needed bookingId={}", booking.getId()); }
        });
        repository.findByBookingStatusAndCreatedAtBefore(BookingStatus.RESERVING, Instant.now().minusSeconds(120)).forEach(booking -> {
            try { service.cancel(booking.getId()); }
            catch (RuntimeException failure) { log.warn("Reservation recovery needed bookingId={}", booking.getId()); }
        });
    }
}