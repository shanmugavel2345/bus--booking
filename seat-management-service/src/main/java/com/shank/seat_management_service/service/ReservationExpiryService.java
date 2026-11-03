package com.shank.seat_management_service.service;

import com.shank.seat_management_service.repository.ReservationRepository;
import java.time.Clock;
import org.slf4j.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "seats.expiry-enabled", havingValue = "true", matchIfMissing = true)
public class ReservationExpiryService {
    private static final Logger log = LoggerFactory.getLogger(ReservationExpiryService.class);
    private final ReservationRepository reservations;
    private final SeatService service;
    private final Clock clock;
    public ReservationExpiryService(ReservationRepository reservations, SeatService service, Clock clock) {
        this.reservations = reservations;
        this.service = service;
        this.clock = clock;
    }
    @Scheduled(fixedDelayString = "${seats.expiry-delay-ms:30000}")
    public void expire() {
        for (Long bookingId : reservations.expired(clock.instant())) {
            try { service.expire(bookingId); }
            catch (RuntimeException failure) { log.warn("Expiry retry needed bookingId={}", bookingId); }
        }
    }
}