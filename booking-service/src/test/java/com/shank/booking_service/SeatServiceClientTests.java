package com.shank.booking_service;

import com.shank.booking_service.entity.Booking;
import com.shank.booking_service.service.SeatServiceClient;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class SeatServiceClientTests {
    private MockRestServiceServer server;
    private SeatServiceClient client;
    private Booking booking;

    @BeforeEach
    void setup() {
        var rest = new RestTemplate();
        server = MockRestServiceServer.bindTo(rest).build();
        client = new SeatServiceClient(rest, "http://seats", "test-token");
        booking = new Booking(12L, "Passenger", "test@example.com", "1234567890", LocalDate.of(2027, 1, 1), 1, new BigDecimal("123.45"));
        ReflectionTestUtils.setField(booking, "id", 42L);
        booking.prepareSeats(List.of("S1"));
    }

    @Test
    void initializesThenReservesUsingActualSeatContractAndToken() {
        initialize();
        server.expect(requestTo("http://seats/api/seats/reserve")).andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Token", "test-token"))
                .andExpect(jsonPath("$.seatNumbers[0]").value("S1"))
                .andExpect(jsonPath("$.bookingId").value(42))
                .andRespond(withSuccess(reservation(), MediaType.APPLICATION_JSON));
        assertThat(client.reserve(booking).reservationId()).isEqualTo("ref-42");
        server.verify();
    }

    @Test
    void existingInventoryIsSafeAndUnavailableSeatReturnsConflict() {
        initialize();
        server.expect(requestTo("http://seats/api/seats/reserve")).andRespond(withStatus(HttpStatus.CONFLICT));
        assertStatus(() -> client.reserve(booking), 409);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "{\"bookingId\":42,\"state\":\"RESERVED\"}"})
    void rejectsMalformedReservations(String response) {
        initialize();
        server.expect(requestTo("http://seats/api/seats/reserve")).andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
        assertStatus(() -> client.reserve(booking), 502);
    }

    @Test
    void callbackSendsReservationReferenceAndRejectsMissingState() {
        booking.reserved("ref-42", Instant.now().plusSeconds(600), List.of("S1"));
        server.expect(requestTo("http://seats/api/seats/release/42")).andExpect(header("X-Internal-Token", "test-token"))
                .andExpect(header("X-Reservation-Id", "ref-42"))
                .andRespond(withSuccess("{\"bookingId\":42}", MediaType.APPLICATION_JSON));
        assertStatus(() -> client.change(booking, "release"), 502);
        server.verify();
    }

    @Test
    void missingHoldCanBeCompensatedButNeverConfirmed() {
        server.expect(requestTo("http://seats/api/seats/cancel/42")).andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo("http://seats/api/seats/confirm/42")).andRespond(withStatus(HttpStatus.NOT_FOUND));
        client.change(booking, "cancel");
        assertStatus(() -> client.change(booking, "confirm"), 404);
    }

    @Test
    void initializationOutageDoesNotAttemptReservation() {
        server.expect(requestTo("http://seats/api/seats/initialize")).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertStatus(() -> client.reserve(booking), 503);
        server.verify();
    }

    private void initialize() {
        server.expect(requestTo("http://seats/api/seats/initialize")).andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Token", "test-token"))
                .andRespond(withStatus(HttpStatus.CONFLICT));
    }

    private String reservation() {
        return "{\"bookingId\":42,\"reservationId\":\"ref-42\",\"state\":\"RESERVED\",\"reservationExpiryTime\":\"2027-01-01T10:00:00Z\","
                + "\"seats\":[{\"id\":1,\"seatNumber\":\"S1\",\"seatStatus\":\"RESERVED\",\"bookingId\":42,\"busId\":12,\"journeyDate\":\"2027-01-01\"}]}";
    }

    private void assertStatus(Runnable action, int status) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ResponseStatusException.class,
                failure -> assertThat(failure.getStatusCode().value()).isEqualTo(status));
    }
}