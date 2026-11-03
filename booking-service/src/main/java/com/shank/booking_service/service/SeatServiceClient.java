package com.shank.booking_service.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.shank.booking_service.entity.Booking;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;
import org.springframework.web.server.ResponseStatusException;

@Component
public class SeatServiceClient {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Seat(Long id, String seatNumber, String seatStatus, Long bookingId, Long busId, LocalDate journeyDate) { }
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Reservation(Long bookingId, String reservationId, String state,
                              Instant reservationExpiryTime, List<Seat> seats) { }
    private record ReserveRequest(Long bookingId, Long busId, LocalDate journeyDate, List<String> seatNumbers, Integer numberOfSeats) { }
    private final RestTemplate rest;
    private final String url;
    private final String token;
    public SeatServiceClient(RestTemplate rest, @Value("${seat.service.url:http://localhost:8084}") String url,
                             @Value("${services.internal-token}") String token) {
        this.rest = rest;
        this.url = url.replaceAll("/+$", "");
        this.token = token;
    }
    public Reservation reserve(Booking booking) {
        try {
            try {
                rest.exchange(url + "/api/seats/initialize", HttpMethod.POST,
                        new HttpEntity<>(Map.of("busId", booking.getBusId(), "journeyDate", booking.getJourneyDate()), headers(null)), Object.class);
            } catch (HttpClientErrorException.Conflict duplicate) { }
            var selection = booking.selectedSeatNumbers();
            var request = new ReserveRequest(booking.getId(), booking.getBusId(), booking.getJourneyDate(),
                    selection.isEmpty() ? null : selection, booking.getNumberOfSeats());
            var response = rest.exchange(url + "/api/seats/reserve", HttpMethod.POST,
                    new HttpEntity<>(request, headers(null)), Reservation.class).getBody();
            if (response == null || !booking.getId().equals(response.bookingId()) || response.reservationId() == null
                    || response.reservationId().isBlank() || !"RESERVED".equals(response.state())
                    || response.reservationExpiryTime() == null || response.seats() == null
                    || response.seats().size() != booking.getNumberOfSeats()
                    || response.seats().stream().anyMatch(seat -> seat == null || seat.seatNumber() == null || !"RESERVED".equals(seat.seatStatus())
                        || !booking.getId().equals(seat.bookingId()) || !booking.getBusId().equals(seat.busId())
                        || !booking.getJourneyDate().equals(seat.journeyDate()))
                    || response.seats().stream().map(Seat::seatNumber).distinct().count() != booking.getNumberOfSeats()
                    || (!selection.isEmpty() && !new HashSet<>(selection).equals(new HashSet<>(response.seats().stream().map(Seat::seatNumber).toList())))) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Invalid seat reservation response");
            }
            return response;
        } catch (RestClientResponseException failure) { throw mapped(failure); }
        catch (RestClientException failure) { throw unavailable(); }
    }
    public void change(Booking booking, String action) {
        try {
            var response = rest.exchange(url + "/api/seats/" + action + "/{id}", HttpMethod.POST,
                    new HttpEntity<Void>(headers(booking.getReservationId())), Reservation.class, booking.getId()).getBody();
            String expected = switch (action) { case "confirm" -> "BOOKED"; case "cancel" -> "CANCELLED"; default -> "RELEASED"; };
            if (response == null || !booking.getId().equals(response.bookingId()) || (!expected.equals(response.state())
                    && !("release".equals(action) && response.state() != null && Set.of("EXPIRED", "CANCELLED").contains(response.state())))) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Invalid seat state response");
            }
        } catch (RestClientResponseException failure) {
            if (!"confirm".equals(action) && failure.getStatusCode().value() == 404) { return; }
            throw mapped(failure);
        } catch (RestClientException failure) { throw unavailable(); }
    }
    private HttpHeaders headers(String reference) {
        var headers = new HttpHeaders();
        headers.set("X-Internal-Token", token);
        if (reference != null) { headers.set("X-Reservation-Id", reference); }
        return headers;
    }
    private ResponseStatusException mapped(RestClientResponseException failure) {
        int status = failure.getStatusCode().value();
        return status == 404 || status == 409 ? new ResponseStatusException(HttpStatus.valueOf(status), "Seat reservation is unavailable") : unavailable();
    }
    private ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Seat service is unavailable");
    }
}