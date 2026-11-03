package com.shank.booking_service.service;

import java.math.BigDecimal;
import com.shank.booking_service.dto.BusResponse;
import com.shank.booking_service.exception.BusServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

@Component
public class BusServiceClient {
    private static final Logger log = LoggerFactory.getLogger(BusServiceClient.class);
    private final RestTemplate restTemplate;
    private final String baseUrl;

    public BusServiceClient(RestTemplate restTemplate, @Value("${services.bus.url:http://localhost:8081}") String baseUrl) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    public BusResponse findById(Long busId) {
        try {
            BusResponse bus = restTemplate.getForObject(baseUrl + "/api/buses/{id}", BusResponse.class, busId);
            if (bus == null || !busId.equals(bus.id()) || bus.fare() == null
                    || bus.fare().signum() <= 0 || bus.fare().stripTrailingZeros().scale() > 2
                    || bus.fare().compareTo(new BigDecimal("99999999999999999.99")) > 0) {
                throw new BusServiceException(HttpStatus.BAD_GATEWAY, "Invalid bus service response");
            }
            return bus;
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 404) {
                throw new BusServiceException(HttpStatus.NOT_FOUND, "Bus not found with id: " + busId);
            }
            log.warn("Bus lookup failed for id={} status={}", busId, exception.getStatusCode());
            throw new BusServiceException(HttpStatus.SERVICE_UNAVAILABLE, "Bus service is unavailable");
        } catch (RestClientException exception) {
            log.warn("Bus lookup unavailable for id={}", busId);
            throw new BusServiceException(HttpStatus.SERVICE_UNAVAILABLE, "Bus service is unavailable");
        }
    }

    public BigDecimal fareFor(Long busId) {
        return findById(busId).fare();
    }
}