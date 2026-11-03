package com.shank.api_gateway;

import java.net.ConnectException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import static org.assertj.core.api.Assertions.assertThat;

class GatewayErrorHandlerTests {
    @Test
    void connectionFailureReturnsSafe503() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/payments"));
        new GatewayErrorHandler().handle(exchange, new RuntimeException(new ConnectException("private host"))).block();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(exchange.getResponse().getBodyAsString().block())
                .contains("Upstream service is unavailable").doesNotContain("private host");
    }
}