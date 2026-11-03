package com.shank.api_gateway;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiGatewayApplicationTests {
    private static final DisposableServer BACKEND = HttpServer.create()
            .host("127.0.0.1").port(0)
            .handle((request, response) -> request.receive().aggregate().asString()
                    .defaultIfEmpty("")
                    .flatMap(body -> response
                            .status(request.uri().contains("/missing") ? 404 : 200)
                            .header("Content-Type", "text/plain")
                            .header("X-Backend", "test-service")
                            .sendString(Mono.just(request.method().name() + " " + request.uri() + " " + body))
                            .then()))
            .bindNow();

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void backendUrls(DynamicPropertyRegistry registry) {
        for (String property : new String[]{"BUS_SERVICE_URL", "BOOKING_SERVICE_URL", "PAYMENT_SERVICE_URL", "SEAT_SERVICE_URL"}) {
            registry.add(property,
                    () -> "http://127.0.0.1:" + BACKEND.port());
        }
    }

    @AfterAll
    static void stopBackend() {
        BACKEND.disposeNow();
    }

    private WebTestClient client() {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @ParameterizedTest
    @CsvSource({"GET,/api/buses/getAllBus?source=Chennai",
            "GET,/api/booking/getAllBookings", "GET,/api/bookings", "GET,/api/payments",
            "GET,/api/payments/booking/1", "PUT,/api/booking/1/cancel", "DELETE,/api/buses/1",
            "GET,/api/seats/bus/1/available?journeyDate=2026-11-10", "GET,/api/seats/booking/1"})
    void forwardsMethodPathAndQuery(String method, String path) {
        client().method(HttpMethod.valueOf(method)).uri(path)
                .exchange().expectStatus().isOk()
                .expectHeader().valueEquals("X-Backend", "test-service")
                .expectBody(String.class).isEqualTo(method + " " + path + " ");
    }

    @ParameterizedTest
    @CsvSource({"POST,/api/buses/createBus", "PUT,/api/buses/1",
            "POST,/api/booking/createBooking", "POST,/api/bookings", "POST,/api/payments", "POST,/api/seats/reserve"})
    void forwardsRequestBody(String method, String path) {
        String body = "{\"bookingId\":1,\"amount\":500,\"paymentMethod\":\"UPI\"}";
        client().method(HttpMethod.valueOf(method)).uri(path)
                .header("Content-Type", "application/json").bodyValue(body)
                .exchange().expectStatus().isOk()
                .expectBody(String.class).isEqualTo(method + " " + path + " " + body);
    }

    @Test
    void preservesBackendErrorStatusAndBody() {
        client().get().uri("/api/payments/missing").exchange()
                .expectStatus().isNotFound()
                .expectHeader().valueEquals("X-Backend", "test-service")
                .expectBody(String.class).isEqualTo("GET /api/payments/missing ");
    }

    @Test
    void rejectsUnconfiguredPaths() {
        client().get().uri("/unconfigured").exchange().expectStatus().isNotFound();
    }

    @Test
    void allowsConfiguredFrontendPreflight() {
        client().options().uri("/api/bookings")
                .header("Origin", "http://localhost:3000")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "Content-Type")
                .exchange().expectStatus().isOk()
                .expectHeader().valueEquals("Access-Control-Allow-Origin", "http://localhost:3000");
    }

    @Test
    void rejectsUntrustedFrontendOrigin() {
        client().options().uri("/api/bookings")
                .header("Origin", "http://untrusted.example")
                .header("Access-Control-Request-Method", "POST")
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void doesNotExposeInternalConfirmation() {
        client().put().uri("/internal/bookings/1/confirm").exchange().expectStatus().isNotFound();
    }
}