package com.shank.booking_service;

import com.shank.booking_service.service.BusServiceClient;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BusClientTests {
    private HttpServer server;
    private BusServiceClient client;
    private int responseStatus;
    private String responseBody;
    private String requestedPath;

    @BeforeEach
    void startBackend() throws Exception {
        responseStatus = 200;
        responseBody = "{\"id\":12,\"fare\":123.45,\"busName\":\"Express\"}";
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestedPath = exchange.getRequestURI().getPath();
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        client = new BusServiceClient(new com.shank.booking_service.config.RestTemplateConfiguration()
            .restTemplate(1000, 1000), "http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stopBackend() {
        server.stop(0);
    }

    @Test
    void retrievesBusFareOverHttp() {
        assertThat(client.fareFor(12L)).isEqualByComparingTo("123.45");
        assertThat(requestedPath).isEqualTo("/api/buses/12");
    }

    @Test
    void mapsMissingBusTo404() {
        responseStatus = 404;
        assertThatThrownBy(() -> client.fareFor(12L))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                    assertThat(exception.getStatusCode().value()).isEqualTo(404);
                    assertThat(exception.getReason()).isEqualTo("Bus not found with id: 12");
                });
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 500, 503})
    void mapsBackendErrorsTo503(int status) {
        responseStatus = status;
        assertThatThrownBy(() -> client.fareFor(12L))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode().value()).isEqualTo(503));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "{\"id\":13,\"fare\":100}",
            "{\"id\":12,\"fare\":0}", "{\"id\":12,\"fare\":-1}",
            "{\"id\":12,\"fare\":1.234}"})
    void rejectsInvalidBusResponses(String body) {
        responseBody = body;
        assertThatThrownBy(() -> client.fareFor(12L))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode().value()).isEqualTo(502));
    }

    @Test
    void handlesConnectionFailure() {
        server.stop(0);
        assertThatThrownBy(() -> client.fareFor(12L))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode().value()).isEqualTo(503));
    }
}