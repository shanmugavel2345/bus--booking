package com.shank.payment_service;

import java.util.UUID;
import com.shank.payment_service.repository.PaymentRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class PaymentApiTests {
        @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

        @Test
        void legacyNativeEnumMigrationPreservesPaymentAndAllowsOutcomes() throws Exception {
                var legacy = repository.saveAndFlush(new com.shank.payment_service.entity.Payment(
                                42L, new java.math.BigDecimal("125.50"), com.shank.payment_service.entity.PaymentMethod.UPI));
                var constraints = jdbc.queryForList("SELECT CONSTRAINT_NAME, CHECK_CLAUSE FROM INFORMATION_SCHEMA.CHECK_CONSTRAINTS "
                                + "WHERE CONSTRAINT_SCHEMA = 'PUBLIC' AND CHECK_CLAUSE LIKE '%PAYMENT_STATUS%'");
                for (var constraint : constraints) {
                        jdbc.execute("ALTER TABLE payments DROP CONSTRAINT \"" + constraint.get("CONSTRAINT_NAME") + "\"");
                }
                jdbc.execute("ALTER TABLE payments ALTER COLUMN payment_status ENUM('PENDING')");
                try {
                        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(
                                        "UPDATE payments SET payment_status = 'SUCCESS' WHERE id = ?", legacy.getId()))
                                        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
                        try (var connection = jdbc.getDataSource().getConnection()) {
                                org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,
                                                new org.springframework.core.io.ClassPathResource("schema.sql"));
                        }
                        var preserved = repository.findById(legacy.getId()).orElseThrow();
                        assertThat(preserved.getTransactionId()).isEqualTo(legacy.getTransactionId());
                        assertThat(preserved.getAmount()).isEqualByComparingTo("125.50");
                        assertThat(preserved.getPaymentStatus()).isEqualTo(com.shank.payment_service.entity.PaymentStatus.PENDING);
                        for (var paymentStatus : com.shank.payment_service.entity.PaymentStatus.values()) {
                                jdbc.update("UPDATE payments SET payment_status = ? WHERE id = ?", paymentStatus.name(), legacy.getId());
                                assertThat(repository.findById(legacy.getId()).orElseThrow().getPaymentStatus()).isEqualTo(paymentStatus);
                        }
                } finally {
                        jdbc.execute("ALTER TABLE payments ALTER COLUMN payment_status VARCHAR(32)");
                        for (var constraint : constraints) {
                                jdbc.execute("ALTER TABLE payments ADD CONSTRAINT \"" + constraint.get("CONSTRAINT_NAME")
                                                + "\" CHECK (" + constraint.get("CHECK_CLAUSE") + ")");
                        }
                }
        }

    @Test
    void ignoresFrontendAmountAndSupportsRequestsWithoutAmount() throws Exception {
        mvc.perform(post("/api/payments").contentType(MediaType.APPLICATION_JSON)
                        .content(request().replace("125.50", "1.00")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.amount").value(125.50));
        repository.deleteAll();
        mvc.perform(post("/api/payments").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bookingId\":42,\"paymentMethod\":\"UPI\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.amount").value(125.50));
    }

    @ParameterizedTest
    @ValueSource(ints = {404, 503})
    void lookupErrorsDoNotSavePayment(int code) throws Exception {
        org.mockito.Mockito.when(bookingClient.findById(42L)).thenThrow(
                new com.shank.payment_service.exception.BookingServiceException(
                        org.springframework.http.HttpStatus.valueOf(code), "Booking lookup failed"));
        mvc.perform(post("/api/payments").contentType(MediaType.APPLICATION_JSON).content(request()))
                .andExpect(status().is(code));
        assertThat(repository.count()).isZero();
        org.mockito.Mockito.verify(bookingClient, org.mockito.Mockito.never()).confirm(org.mockito.ArgumentMatchers.anyLong());
    }

    @ParameterizedTest
    @ValueSource(strings = {"CANCELLED", "CONFIRMED"})
    void rejectsIneligibleBooking(String bookingStatus) throws Exception {
        org.mockito.Mockito.when(bookingClient.findById(42L)).thenReturn(
                new com.shank.payment_service.dto.BookingResponse(42L, 12L, "Passenger", "test@example.com",
                        "1234567890", java.time.LocalDate.now(), 1, new java.math.BigDecimal("125.50"), bookingStatus));
        mvc.perform(post("/api/payments").contentType(MediaType.APPLICATION_JSON).content(request()))
                .andExpect(status().isConflict());
        assertThat(repository.count()).isZero();
    }

    @Test
    void failedSimulationNeverConfirmsBooking() throws Exception {
        var service = new com.shank.payment_service.service.PaymentService(repository, bookingClient, false);
        var result = service.create(new com.shank.payment_service.dto.PaymentRequest(
                42L, null, com.shank.payment_service.entity.PaymentMethod.UPI));
        assertThat(result.paymentStatus()).isEqualTo(com.shank.payment_service.entity.PaymentStatus.FAILED);
        assertThat(repository.findById(result.id()).orElseThrow().getPaymentStatus())
                .isEqualTo(com.shank.payment_service.entity.PaymentStatus.FAILED);
        org.mockito.Mockito.verify(bookingClient, org.mockito.Mockito.never()).confirm(42L);
        org.mockito.Mockito.verify(bookingClient).paymentFailed(42L);
        assertThat(repository.findById(result.id()).orElseThrow().getNotificationPending()).isFalse();
        mvc.perform(post("/api/payments/{id}/confirm-booking", result.id())).andExpect(status().isConflict());
    }

    @Test
    void failedOutcomeNotificationIsPersistedAndRetryable() {
        org.mockito.Mockito.doThrow(new com.shank.payment_service.exception.BookingServiceException(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "Booking unavailable"))
                .when(bookingClient).paymentFailed(42L);
        var service = new com.shank.payment_service.service.PaymentService(repository, bookingClient, false);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.create(new com.shank.payment_service.dto.PaymentRequest(
                42L, null, com.shank.payment_service.entity.PaymentMethod.UPI))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        var saved = repository.findAll().get(0);
        assertThat(saved.getPaymentStatus().name()).isEqualTo("FAILED");
        assertThat(saved.getNotificationPending()).isTrue();
        org.mockito.Mockito.doNothing().when(bookingClient).paymentFailed(42L);
        service.retryNotification(saved.getId());
        assertThat(repository.findById(saved.getId()).orElseThrow().getNotificationPending()).isFalse();
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
        void expiredReservationIsRejectedBeforePayment() throws Exception {
                org.mockito.Mockito.when(bookingClient.findById(42L)).thenReturn(
                                new com.shank.payment_service.dto.BookingResponse(42L, 12L, "Passenger", "test@example.com", "1234567890",
                                                java.time.LocalDate.now(), 1, new java.math.BigDecimal("125.50"), "PENDING_PAYMENT", java.time.Instant.now().minusSeconds(1)));
                mvc.perform(post("/api/payments").contentType(MediaType.APPLICATION_JSON).content(request())).andExpect(status().isConflict());
                assertThat(repository.count()).isZero();
        }

        @Test
    void successfulPaymentSurvivesNotificationFailureAndCanRetry() throws Exception {
        org.mockito.Mockito.doThrow(new com.shank.payment_service.exception.BookingServiceException(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "Booking service is unavailable"))
                .when(bookingClient).confirm(42L);
        mvc.perform(post("/api/payments").contentType(MediaType.APPLICATION_JSON).content(request()))
                .andExpect(status().isServiceUnavailable());
        var payment = repository.findAll().get(0);
        assertThat(payment.getPaymentStatus()).isEqualTo(com.shank.payment_service.entity.PaymentStatus.SUCCESS);
        org.mockito.Mockito.doNothing().when(bookingClient).confirm(42L);
        mvc.perform(post("/api/payments/{id}/confirm-booking", payment.getId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.paymentStatus").value("SUCCESS"));
        assertThat(repository.count()).isEqualTo(1);
    }
    @Autowired private MockMvc mvc;
    @Autowired private PaymentRepository repository;
        @org.springframework.test.context.bean.override.mockito.MockitoBean
        private com.shank.payment_service.service.BookingServiceClient bookingClient;

    @BeforeEach
    void cleanDatabase() {
        repository.deleteAll();
        org.mockito.Mockito.when(bookingClient.findById(42L)).thenReturn(
                new com.shank.payment_service.dto.BookingResponse(42L, 12L, "Passenger", "test@example.com",
                        "1234567890", java.time.LocalDate.now(), 1, new java.math.BigDecimal("125.50"), "PENDING"));
    }

    private String request() {
        return """
                {"bookingId":42,"amount":125.50,"paymentMethod":"UPI"}
                """;
    }

    @Test
    void createsPersistsAndReadsPayment() throws Exception {
        mvc.perform(post("/api/payments").contentType(MediaType.APPLICATION_JSON).content(request()))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.bookingId").value(42))
                .andExpect(jsonPath("$.amount").value(125.50))
                .andExpect(jsonPath("$.paymentMethod").value("UPI"))
                .andExpect(jsonPath("$.paymentStatus").value("SUCCESS"))
                .andExpect(jsonPath("$.paymentDate").exists());
        var saved = repository.findAll().get(0);
        assertThat(UUID.fromString(saved.getTransactionId())).isNotNull();
        assertThat(saved.getAmount()).isEqualByComparingTo("125.50");
        mvc.perform(get("/api/payments/{id}", saved.getId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(saved.getId()));
        mvc.perform(get("/api/payments"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/payments/booking/42"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/payments/booking/43"))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"UPI", "CARD", "NET_BANKING"})
    void acceptsSupportedMethods(String method) throws Exception {
        mvc.perform(post("/api/payments").contentType(MediaType.APPLICATION_JSON)
                        .content(request().replace("UPI", method)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.paymentMethod").value(method));
    }

    @Test
        void repeatedRequestsDoNotCreateDuplicatePayments() throws Exception {
                mvc.perform(post("/api/payments").contentType(MediaType.APPLICATION_JSON).content(request()))
                                .andExpect(status().isCreated());
                mvc.perform(post("/api/payments").contentType(MediaType.APPLICATION_JSON).content(request()))
                                .andExpect(status().isConflict());
                assertThat(repository.findAll()).hasSize(1);
                org.mockito.Mockito.verify(bookingClient).confirm(42L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{", "null", "", "badMethod", "zero", "negative", "precision",
            "overflow", "badBooking", "status", "credential", "transaction"})
    void rejectsInvalidRequestsWithoutPersisting(String input) throws Exception {
        String body = switch (input) {
            case "badMethod" -> request().replace("UPI", "CASH");
            case "zero" -> request().replace("125.50", "0");
            case "negative" -> request().replace("125.50", "-1");
            case "precision" -> request().replace("125.50", "1.001");
            case "overflow" -> request().replace("125.50", "100000000000000000");
            case "badBooking" -> request().replace("42", "0");
            case "status" -> request().replace("}", ",\"paymentStatus\":\"SUCCESS\"}");
            case "credential" -> request().replace("}", ",\"cardNumber\":\"1234\"}");
            case "transaction" -> request().replace("}", ",\"transactionId\":\"supplied\"}");
            default -> input;
        };
        mvc.perform(post("/api/payments").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.path").value("/api/payments"));
        assertThat(repository.count()).isZero();
    }

    @Test
    void missingAndMalformedLookupsReturnStructuredErrors() throws Exception {
        mvc.perform(get("/api/payments/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Payment not found with id: 999999"));
        mvc.perform(get("/api/payments/invalid"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Malformed request"));
        mvc.perform(get("/api/payments/booking/invalid"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/payments"))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
    }

    @Test
    void swaggerAndOpenApiAreAvailable() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/payments'].post").exists())
                .andExpect(jsonPath("$.paths['/api/payments/booking/{bookingId}'].get").exists());
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    }
}