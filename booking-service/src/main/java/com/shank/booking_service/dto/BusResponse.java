package com.shank.booking_service.dto;

import java.math.BigDecimal;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record BusResponse(Long id, String busName, String busNumber, String source,
                          String destination, Integer totalSeates, BigDecimal fare) {
}