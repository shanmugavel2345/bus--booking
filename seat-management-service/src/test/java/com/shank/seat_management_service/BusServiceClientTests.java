package com.shank.seat_management_service;

import com.shank.seat_management_service.service.BusServiceClient;
import org.junit.jupiter.api.*;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class BusServiceClientTests {
    private BusServiceClient client;
    private MockRestServiceServer server;
    @BeforeEach
    void setup() {
        var rest = new RestTemplate();
        server = MockRestServiceServer.bindTo(rest).build();
        client = new BusServiceClient(rest, "http://bus/");
    }
    @Test
    void usesActualBusPathAndMisspelledCapacityField() {
        server.expect(requestTo("http://bus/api/buses/12")).andRespond(withSuccess(
                "{\"id\":12,\"totalSeates\":40,\"fare\":123.45}", MediaType.APPLICATION_JSON));
        assertThat(client.findById(12L).totalSeates()).isEqualTo(40);
        server.verify();
    }
    @Test
    void mapsMissingBus() {
        server.expect(anything()).andRespond(withResourceNotFound());
        assertThatThrownBy(() -> client.findById(12L)).isInstanceOfSatisfying(ResponseStatusException.class,
                failure -> assertThat(failure.getStatusCode().value()).isEqualTo(404));
    }
    @Test
    void mapsUnavailableBus() {
        server.expect(anything()).andRespond(withServerError());
        assertThatThrownBy(() -> client.findById(12L)).isInstanceOfSatisfying(ResponseStatusException.class,
                failure -> assertThat(failure.getStatusCode().value()).isEqualTo(503));
    }
    @Test
    void rejectsInvalidCapacity() {
        server.expect(anything()).andRespond(withSuccess("{\"id\":12,\"totalSeats\":40}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.findById(12L)).isInstanceOfSatisfying(ResponseStatusException.class,
                failure -> assertThat(failure.getStatusCode().value()).isEqualTo(502));
    }
}