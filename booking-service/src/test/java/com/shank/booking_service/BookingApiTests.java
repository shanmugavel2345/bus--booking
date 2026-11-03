package com.shank.booking_service;

import java.time.LocalDate;
import com.shank.booking_service.repository.BookingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class BookingApiTests {
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Test
    void legacyNativeEnumMigrationPreservesBookingAndAllowsNewStates() throws Exception {
        var legacy = repository.saveAndFlush(new com.shank.booking_service.entity.Booking(
            12L, "Legacy Passenger", "legacy@example.com", "1234567890",
            LocalDate.now(), 1, new java.math.BigDecimal("500.00")));
        var constraints = jdbc.queryForList("SELECT CONSTRAINT_NAME, CHECK_CLAUSE FROM INFORMATION_SCHEMA.CHECK_CONSTRAINTS "
            + "WHERE CONSTRAINT_SCHEMA = 'PUBLIC' AND CHECK_CLAUSE LIKE '%BOOKING_STATUS%'");
        for (var constraint : constraints) {
            jdbc.execute("ALTER TABLE booking DROP CONSTRAINT \"" + constraint.get("CONSTRAINT_NAME") + "\"");
        }
        jdbc.execute("ALTER TABLE booking ALTER COLUMN booking_status ENUM('CANCELLED', 'CONFIRMED', 'PENDING')");
        try {
            assertThatThrownBy(() -> repository.findByBookingStatusAndCreatedAtBefore(
                com.shank.booking_service.entity.BookingStatus.RESERVING, java.time.Instant.now()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            try (var connection = jdbc.getDataSource().getConnection()) {
                org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
                    new org.springframework.core.io.ClassPathResource("schema.sql"));
            }
            var preserved = repository.findById(legacy.getId()).orElseThrow();
            assertThat(preserved.getPassengerName()).isEqualTo("Legacy Passenger");
            assertThat(preserved.getTotalAmount()).isEqualByComparingTo("500.00");
            assertThat(preserved.getBookingStatus()).isEqualTo(com.shank.booking_service.entity.BookingStatus.PENDING);
            for (var bookingStatus : com.shank.booking_service.entity.BookingStatus.values()) {
                jdbc.update("UPDATE booking SET booking_status = ? WHERE id = ?", bookingStatus.name(), legacy.getId());
                assertThat(repository.findById(legacy.getId()).orElseThrow().getBookingStatus()).isEqualTo(bookingStatus);
            }
            assertThat(repository.findByBookingStatusAndCreatedAtBefore(
                com.shank.booking_service.entity.BookingStatus.RESERVING, java.time.Instant.now())).isEmpty();
        } finally {
            jdbc.execute("ALTER TABLE booking ALTER COLUMN booking_status VARCHAR(32)");
            for (var constraint : constraints) {
                jdbc.execute("ALTER TABLE booking ADD CONSTRAINT \"" + constraint.get("CONSTRAINT_NAME")
                    + "\" CHECK (" + constraint.get("CHECK_CLAUSE") + ")");
            }
        }
    }

    @Test
    void pluralEndpointAcceptsCorrectDateSpelling() throws Exception {
    mvc.perform(post("/api/bookings").contentType(MediaType.APPLICATION_JSON)
            .content(request().replace("jouneyDate", "journeyDate")))
        .andExpect(status().isCreated()).andExpect(jsonPath("$.totalAmount").value(1500.00));
    mvc.perform(get("/api/bookings")).andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void confirmationRequiresAuthenticationAndIsIdempotent() throws Exception {
    Long id = createBooking();
    mvc.perform(put("/internal/bookings/{id}/confirm", id)).andExpect(status().isUnauthorized());
    mvc.perform(put("/internal/bookings/{id}/confirm", id).header("X-Internal-Token", "wrong"))
        .andExpect(status().isUnauthorized());
    for (int attempt = 0; attempt < 2; attempt++) {
        mvc.perform(put("/internal/bookings/{id}/confirm", id).header("X-Internal-Token", "test-internal-token"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.bookingStatus").value("CONFIRMED"));
    }
    assertThat(repository.findById(id).orElseThrow().getBookingStatus().name()).isEqualTo("CONFIRMED");
    }

    @Test
    void cancelledAndMissingBookingsCannotBeConfirmed() throws Exception {
    Long id = createBooking();
    mvc.perform(put("/api/bookings/{id}/cancel", id)).andExpect(status().isOk());
    mvc.perform(put("/internal/bookings/{id}/confirm", id).header("X-Internal-Token", "test-internal-token"))
        .andExpect(status().isConflict());
    mvc.perform(put("/internal/bookings/999999/confirm").header("X-Internal-Token", "test-internal-token"))
        .andExpect(status().isNotFound());
    }
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.shank.booking_service.service.BusServiceClient busClient;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.shank.booking_service.service.SeatServiceClient seatClient;
    @Autowired private com.shank.booking_service.service.BookingService bookingService;
    @Autowired private MockMvc mvc;
    @Autowired private BookingRepository repository;

    @BeforeEach
    void cleanDatabase() {
        repository.deleteAll();
        org.mockito.Mockito.when(busClient.fareFor(12L)).thenReturn(new java.math.BigDecimal("500.00"));
        org.mockito.Mockito.when(seatClient.reserve(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            var booking = invocation.getArgument(0, com.shank.booking_service.entity.Booking.class);
            var numbers = booking.selectedSeatNumbers().isEmpty() ? java.util.List.of("S1", "S2", "S3") : booking.selectedSeatNumbers();
            return new com.shank.booking_service.service.SeatServiceClient.Reservation(booking.getId(), "reservation-" + booking.getId(),
                    "RESERVED", java.time.Instant.now().plusSeconds(600), numbers.stream().map(number ->
                        new com.shank.booking_service.service.SeatServiceClient.Seat(1L, number, "RESERVED", booking.getId(),
                            booking.getBusId(), booking.getJourneyDate())).toList());
        });
    }

    private String request() {
        return """
                {"busId":12,"passengerName":"Test Passenger",
                 "passengerEmail":"test@example.com","passengerPhone":"1234567890",
                 "jouneyDate":"%s","numberOfSeats":3,
                 "totalAmount":1,"bookingStatus":"CONFIRMED"}
                """.formatted(LocalDate.now());
    }

    private Long createBooking() throws Exception {
        mvc.perform(post("/api/booking/createBooking")
                        .contentType(MediaType.APPLICATION_JSON).content(request()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.busId").value(12))
                .andExpect(jsonPath("$.passengerName").value("Test Passenger"))
                .andExpect(jsonPath("$.passengerEmail").value("test@example.com"))
                .andExpect(jsonPath("$.passengerPhone").value("1234567890"))
                .andExpect(jsonPath("$.journeyDate").value(LocalDate.now().toString()))
                .andExpect(jsonPath("$.jouneyDate").doesNotExist())
                .andExpect(jsonPath("$.numberOfSeats").value(3))
                .andExpect(jsonPath("$.totalAmount").value(1500.00))
                .andExpect(jsonPath("$.bookingStatus").value("PENDING_PAYMENT"));
        return repository.findAll().get(0).getId();
    }

        @Test
        void selectedSeatsAreStoredAndReturned() throws Exception {
        mvc.perform(post("/api/bookings").contentType(MediaType.APPLICATION_JSON)
            .content(request().replace("\"numberOfSeats\":3", "\"numberOfSeats\":3,\"seatNumbers\":[\"S4\",\"S5\",\"S6\"]")))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.seatNumbers[0]").value("S4"))
            .andExpect(jsonPath("$.reservationId").isString());
        assertThat(repository.findAll().get(0).selectedSeatNumbers()).containsExactly("S4", "S5", "S6");
        }

        @Test
        void rejectsSeatCountMismatch() throws Exception {
        mvc.perform(post("/api/bookings").contentType(MediaType.APPLICATION_JSON)
            .content(request().replace("\"numberOfSeats\":3", "\"numberOfSeats\":3,\"seatNumbers\":[\"S1\"]")))
            .andExpect(status().isBadRequest());
        assertThat(repository.count()).isZero();
        }

        @Test
        void reservationFailureCompensatesAndRetainsAuditRecord() throws Exception {
            org.mockito.Mockito.doThrow(new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "Seat service is unavailable"))
                .when(seatClient).reserve(org.mockito.ArgumentMatchers.any());
        mvc.perform(post("/api/bookings").contentType(MediaType.APPLICATION_JSON).content(request()))
            .andExpect(status().isServiceUnavailable());
        assertThat(repository.findAll()).singleElement().extracting(booking -> booking.getBookingStatus().name()).isEqualTo("CANCELLED");
        org.mockito.Mockito.verify(seatClient).change(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("cancel"));
        }

        @Test
        void failedCompensationIsDurableAndRetryable() throws Exception {
            org.mockito.Mockito.doThrow(new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE)).when(seatClient).reserve(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.doThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE))
            .when(seatClient).change(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("cancel"));
        mvc.perform(post("/api/bookings").contentType(MediaType.APPLICATION_JSON).content(request())).andExpect(status().isServiceUnavailable());
        var saved = repository.findAll().get(0);
        assertThat(saved.getPendingSeatAction()).isEqualTo("cancel");
        assertThat(saved.getBookingStatus().name()).isEqualTo("CANCEL_PENDING");
        org.mockito.Mockito.doNothing().when(seatClient).change(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("cancel"));
        assertThat(bookingService.processPending(saved.getId()).bookingStatus().name()).isEqualTo("CANCELLED");
        }

        @Test
        void bookingIsConfirmedOnlyAfterSeatsAcknowledge() throws Exception {
        Long id = createBooking();
        org.mockito.Mockito.doThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE))
            .when(seatClient).change(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("confirm"));
        mvc.perform(put("/internal/bookings/{id}/confirm", id).header("X-Internal-Token", "test-internal-token"))
            .andExpect(status().isServiceUnavailable());
        assertThat(repository.findById(id).orElseThrow().getBookingStatus().name()).isEqualTo("CONFIRMING");
        org.mockito.Mockito.doNothing().when(seatClient).change(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("confirm"));
        assertThat(bookingService.processPending(id).bookingStatus().name()).isEqualTo("CONFIRMED");
        }

        @Test
        void failedPaymentReleasesSeatsAndCannotLaterConfirm() throws Exception {
        Long id = createBooking();
        mvc.perform(put("/internal/bookings/{id}/payment-failed", id)).andExpect(status().isUnauthorized());
        mvc.perform(put("/internal/bookings/{id}/payment-failed", id).header("X-Internal-Token", "test-internal-token"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.bookingStatus").value("PAYMENT_FAILED"));
        org.mockito.Mockito.verify(seatClient).change(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("release"));
        mvc.perform(put("/internal/bookings/{id}/confirm", id).header("X-Internal-Token", "test-internal-token"))
            .andExpect(status().isConflict());
        }

    @Test
    void createsAndPersistsServerCalculatedBooking() throws Exception {
        Long id = createBooking();
        var saved = repository.findById(id).orElseThrow();
        assertThat(saved.getTotalAmount()).isEqualByComparingTo("1500.00");
        assertThat(saved.getBookingStatus().name()).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    void listsEmptyAndPopulatedBookingsAndLooksUpById() throws Exception {
        mvc.perform(get("/api/booking/getAllBookings"))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        Long id = createBooking();
        mvc.perform(get("/api/booking/getAllBookings"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(id));
        mvc.perform(get("/api/booking/{id}", id))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
    }

    @Test
    void cancellationIsPersistentAndIdempotentWithoutDeleting() throws Exception {
        Long id = createBooking();
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(put("/api/booking/{id}/cancel", id))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(id))
                    .andExpect(jsonPath("$.bookingStatus").value("CANCELLED"))
                    .andExpect(jsonPath("$.totalAmount").value(1500));
        }
        mvc.perform(get("/api/booking/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingStatus").value("CANCELLED"));
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void missingLookupAndCancellationReturn404() throws Exception {
        mvc.perform(get("/api/booking/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Booking not found with id: 999999"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.details").doesNotExist());
        mvc.perform(put("/api/booking/999999/cancel"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Booking not found with id: 999999"));
    }

    @Test
    void missingRequiredFieldsHaveValidationDetails() throws Exception {
        mvc.perform(post("/api/booking/createBooking")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.details.busId").exists())
                .andExpect(jsonPath("$.details.passengerName").exists())
                .andExpect(jsonPath("$.details.passengerEmail").exists())
                .andExpect(jsonPath("$.details.passengerPhone").exists())
                .andExpect(jsonPath("$.details.jouneyDate").exists())
                .andExpect(jsonPath("$.details.numberOfSeats").exists());
        assertThat(repository.count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"name", "email", "phone", "date", "seats", "blankEmail"})
    void rejectsInvalidFields(String field) throws Exception {
        String body = switch (field) {
            case "name" -> request().replace("Test Passenger", " ");
            case "email" -> request().replace("test@example.com", "invalid");
            case "blankEmail" -> request().replace("test@example.com", " ");
            case "phone" -> request().replace("1234567890", " ");
            case "date" -> request().replace(LocalDate.now().toString(), LocalDate.now().minusDays(1).toString());
            default -> request().replace("\"numberOfSeats\":3", "\"numberOfSeats\":0");
        };
        mvc.perform(post("/api/booking/createBooking").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.details").isMap());
        assertThat(repository.count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "", "null", "invalidDate", "invalidSeats"})
    void malformedBodiesReturn400(String input) throws Exception {
        String body = switch (input) {
            case "invalidDate" -> request().replace(LocalDate.now().toString(), "2026-02-30");
            case "invalidSeats" -> request().replace("\"numberOfSeats\":3", "\"numberOfSeats\":\"abc\"");
            default -> input;
        };
        mvc.perform(post("/api/booking/createBooking").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Malformed request"))
                .andExpect(jsonPath("$.details").doesNotExist());
    }

    @Test
    void malformedPathVariablesReturn400() throws Exception {
        mvc.perform(get("/api/booking/not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Malformed request"));
        mvc.perform(put("/api/booking/not-a-number/cancel"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Malformed request"));
    }

            @Test
            void busDecimalFareIsUsedExactly() {
            org.mockito.Mockito.when(busClient.fareFor(12L)).thenReturn(new java.math.BigDecimal("123.45"));
            var result = bookingService.create(new com.shank.booking_service.dto.BookingRequest(
                12L, "Test", "test@example.com", "1234567890", LocalDate.now(), 3));
            assertThat(result.totalAmount()).isEqualByComparingTo("370.35");
            }

            @Test
            void missingBusDoesNotCreateBooking() throws Exception {
            org.mockito.Mockito.when(busClient.fareFor(12L)).thenThrow(
                new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "Bus not found with id: 12"));
            mvc.perform(post("/api/booking/createBooking").contentType(MediaType.APPLICATION_JSON).content(request()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Bus not found with id: 12"));
            assertThat(repository.count()).isZero();
            }

            @Test
            void unavailableBusDoesNotCreateBooking() throws Exception {
            org.mockito.Mockito.when(busClient.fareFor(12L)).thenThrow(
                new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "Bus service is unavailable"));
            mvc.perform(post("/api/booking/createBooking").contentType(MediaType.APPLICATION_JSON).content(request()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("Bus service is unavailable"));
            assertThat(repository.count()).isZero();
            }

            @Test
            void openApiAndSwaggerAreAvailable() throws Exception {
            mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/booking/createBooking'].post").exists())
                .andExpect(jsonPath("$.components.schemas.BookingRequest.properties.jouneyDate").exists())
                .andExpect(jsonPath("$.components.schemas.BookingResponse.properties.journeyDate").exists());
            mvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk());
            }

            @Test
            void unexpectedErrorBodyDoesNotExposeExceptionDetails() {
            var handler = new com.shank.booking_service.exception.GlobalExceptionHandler();
            var response = handler.unexpected(new IllegalStateException("internal failure"));
            assertThat(response.getStatusCode().value()).isEqualTo(500);
            assertThat(response.getBody()).containsOnlyKeys("timestamp", "status", "error", "message")
                .containsEntry("message", "Unexpected error occurred")
                .containsEntry("status", 500)
                .containsEntry("error", "Internal Server Error");
            }
}