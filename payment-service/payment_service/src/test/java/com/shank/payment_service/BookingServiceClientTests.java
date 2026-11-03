package com.shank.payment_service;

import com.shank.payment_service.config.RestTemplateConfiguration;
import com.shank.payment_service.service.BookingServiceClient;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;

class BookingServiceClientTests {
    private HttpServer server;
    private BookingServiceClient client;
    private String body;
    private int status;
    private String method;
    private String path;
    private String token;

    @BeforeEach
    void startBackend() throws Exception {
        status = 200;
        body = "{\"id\":42,\"busId\":12,\"journeyDate\":\"2027-01-01\",\"totalAmount\":123.45,\"bookingStatus\":\"PENDING\"}";
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            method = exchange.getRequestMethod();
            path = exchange.getRequestURI().getPath();
            token = exchange.getRequestHeaders().getFirst("X-Internal-Token");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
        client = new BookingServiceClient(new RestTemplateConfiguration().restTemplate(1000, 1000),
                "http://127.0.0.1:" + server.getAddress().getPort(), "test-token");
    }

    @AfterEach
    void stopBackend() {
        server.stop(0);
    }

    @Test
    void readsActualBookingContract() {
        assertThat(client.findById(42L).totalAmount()).isEqualByComparingTo("123.45");
        assertThat(method).isEqualTo("GET");
        assertThat(path).isEqualTo("/api/bookings/42");
        assertThat(token).isNull();
    }

    @Test
    void confirmsOverAuthenticatedInternalEndpoint() {
        body = body.replace("PENDING", "CONFIRMED");
        client.confirm(42L);
        assertThat(method).isEqualTo("PUT");
        assertThat(path).isEqualTo("/internal/bookings/42/confirm");
        assertThat(token).isEqualTo("test-token");
    }

    @ParameterizedTest
    @ValueSource(ints = {404, 409, 500, 503})
    void mapsHttpErrors(int upstreamStatus) {
        status = upstreamStatus;
        int expected = status == 404 || status == 409 ? status : 503;
        assertThatThrownBy(() -> client.findById(42L)).isInstanceOfSatisfying(ResponseStatusException.class,
                exception -> assertThat(exception.getStatusCode().value()).isEqualTo(expected));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "{\"id\":43,\"totalAmount\":123,\"bookingStatus\":\"PENDING\"}",
            "{\"id\":42,\"totalAmount\":-1,\"bookingStatus\":\"PENDING\"}",
            "{\"id\":42,\"totalAmount\":1.234,\"bookingStatus\":\"PENDING\"}"})
    void rejectsInvalidBookingResponses(String response) {
        body = response;
        assertThatThrownBy(() -> client.findById(42L)).isInstanceOfSatisfying(ResponseStatusException.class,
                exception -> assertThat(exception.getStatusCode().value()).isEqualTo(502));
    }

    @Test
    void rejectsUnconfirmedAcknowledgement() {
        assertThatThrownBy(() -> client.confirm(42L)).isInstanceOfSatisfying(ResponseStatusException.class,
                exception -> assertThat(exception.getStatusCode().value()).isEqualTo(502));
    }

    @Test
    void failedOutcomeUsesAuthenticatedReleaseNotification() {
        body = body.replace("PENDING", "PAYMENT_FAILED");
        client.paymentFailed(42L);
        assertThat(method).isEqualTo("PUT");
        assertThat(path).isEqualTo("/internal/bookings/42/payment-failed");
        assertThat(token).isEqualTo("test-token");
    }

    @Test
    void connectionFailureIsUnavailable() {
        server.stop(0);
        assertThatThrownBy(() -> client.findById(42L)).isInstanceOfSatisfying(ResponseStatusException.class,
                exception -> assertThat(exception.getStatusCode().value()).isEqualTo(503));
    }
}