package com.shank.seat_management_service.service;

import com.shank.seat_management_service.dto.SeatContracts.BusResponse;
import com.shank.seat_management_service.exception.SeatException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;

@Component
public class BusServiceClient {
    private final RestTemplate restTemplate;
    private final String url;
    public BusServiceClient(RestTemplate restTemplate, @Value("${bus.service.url:http://localhost:8081}") String url) {
        this.restTemplate = restTemplate;
        this.url = url.replaceAll("/+$", "");
    }
    public BusResponse findById(Long busId) {
        try {
            var bus = restTemplate.getForObject(url + "/api/buses/{id}", BusResponse.class, busId);
            if (bus == null || !busId.equals(bus.id()) || bus.totalSeates() == null
                    || bus.totalSeates() < 1 || bus.totalSeates() > 1000) {
                throw new SeatException(HttpStatus.BAD_GATEWAY, "Invalid bus capacity response");
            }
            return bus;
        } catch (RestClientResponseException failure) {
            if (failure.getStatusCode().value() == 404) { throw new SeatException(HttpStatus.NOT_FOUND, "Bus not found"); }
            throw new SeatException(HttpStatus.SERVICE_UNAVAILABLE, "Bus service is unavailable");
        } catch (RestClientException failure) {
            throw new SeatException(HttpStatus.SERVICE_UNAVAILABLE, "Bus service is unavailable");
        }
    }
}