package com.shank.api_gateway;

import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

@Component
@Order(-2)
public class GatewayErrorHandler implements WebExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GatewayErrorHandler.class);

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable failure) {
        Throwable cause = failure;
        while (cause != null) {
            if (cause instanceof ConnectException || cause instanceof TimeoutException
                    || cause instanceof io.netty.handler.timeout.ReadTimeoutException) {
                if (exchange.getResponse().isCommitted()) {
                    return Mono.error(failure);
                }
                log.warn("Upstream unavailable for {} {}", exchange.getRequest().getMethod(),
                        exchange.getRequest().getPath());
                exchange.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
                exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
                byte[] body = "{\"status\":503,\"error\":\"Service Unavailable\",\"message\":\"Upstream service is unavailable\"}"
                        .getBytes(StandardCharsets.UTF_8);
                return exchange.getResponse().writeWith(Mono.just(
                        exchange.getResponse().bufferFactory().wrap(body)));
            }
            cause = cause.getCause();
        }
        return Mono.error(failure);
    }
}