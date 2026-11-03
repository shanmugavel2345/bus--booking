package com.shank.payment_service.service;

import java.math.BigDecimal;
import com.shank.payment_service.dto.BookingResponse;
import com.shank.payment_service.exception.BookingServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

@Component
public class BookingServiceClient {
    private static final Logger log = LoggerFactory.getLogger(BookingServiceClient.class);
    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final String token;

    public BookingServiceClient(RestTemplate restTemplate,
            @Value("${services.booking.url:http://localhost:8082}") String baseUrl,
            @Value("${services.internal-token}") String token) {
        if (token.isBlank()) {
            throw new IllegalArgumentException("Internal service token must not be blank");
        }
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.token = token;
    }

    public BookingResponse findById(Long bookingId) {
        return exchange(bookingId, null);
    }

    public void confirm(Long bookingId) {
        BookingResponse booking = exchange(bookingId, "confirm");
        if (!"CONFIRMED".equals(booking.bookingStatus())) {
            throw new BookingServiceException(HttpStatus.BAD_GATEWAY, "Invalid booking confirmation response");
        }
    }

    public void paymentFailed(Long bookingId) {
        BookingResponse booking = exchange(bookingId, "payment-failed");
        if (!java.util.Set.of("PAYMENT_FAILED", "CANCELLED").contains(booking.bookingStatus())) {
            throw new BookingServiceException(HttpStatus.BAD_GATEWAY, "Invalid booking release response");
        }
    }

    private BookingResponse exchange(Long bookingId, String action) {
        try {
            BookingResponse booking;
            if (action != null) {
                HttpHeaders headers = new HttpHeaders();
                headers.set("X-Internal-Token", token);
                booking = restTemplate.exchange(baseUrl + "/internal/bookings/{id}/" + action, HttpMethod.PUT,
                        new HttpEntity<Void>(headers), BookingResponse.class, bookingId).getBody();
            } else {
                booking = restTemplate.getForObject(baseUrl + "/api/bookings/{id}", BookingResponse.class, bookingId);
            }
            if (booking == null || !bookingId.equals(booking.id()) || booking.totalAmount() == null
                    || booking.totalAmount().signum() <= 0
                    || booking.totalAmount().stripTrailingZeros().scale() > 2
                    || booking.totalAmount().compareTo(new BigDecimal("99999999999999999.99")) > 0
                        || !java.util.Set.of("PENDING", "RESERVING", "PENDING_PAYMENT", "CONFIRMING", "CONFIRMED",
                            "CANCEL_PENDING", "CANCELLED", "PAYMENT_FAILED", "EXPIRED").contains(
                            booking.bookingStatus() == null ? "" : booking.bookingStatus())) {
                throw new BookingServiceException(HttpStatus.BAD_GATEWAY, "Invalid booking service response");
            }
            return booking;
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 404) {
                throw new BookingServiceException(HttpStatus.NOT_FOUND, "Booking not found with id: " + bookingId);
            }
            if (exception.getStatusCode().value() == 409) {
                throw new BookingServiceException(HttpStatus.CONFLICT, "Booking is not eligible for confirmation");
            }
            log.warn("Booking request failed id={} status={}", bookingId, exception.getStatusCode());
            throw new BookingServiceException(HttpStatus.SERVICE_UNAVAILABLE, "Booking service is unavailable");
        } catch (RestClientException exception) {
            log.warn("Booking request unavailable id={}", bookingId);
            throw new BookingServiceException(HttpStatus.SERVICE_UNAVAILABLE, "Booking service is unavailable");
        }
    }
}