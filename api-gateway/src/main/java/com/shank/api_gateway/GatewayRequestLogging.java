package com.shank.api_gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class GatewayRequestLogging implements GlobalFilter {
    private static final Logger log = LoggerFactory.getLogger(GatewayRequestLogging.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return chain.filter(exchange).doFinally(signal -> log.info("{} {} status={}",
                exchange.getRequest().getMethod(), exchange.getRequest().getPath(),
                exchange.getResponse().getStatusCode()));
    }
}