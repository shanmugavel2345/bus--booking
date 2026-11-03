package com.shank.booking_service.dto;

import java.time.LocalDate;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.*;

public record BookingRequest(
        @NotNull @Positive Long busId,
        @NotBlank String passengerName,
        @NotBlank @Email String passengerEmail,
        @NotBlank String passengerPhone,
        @JsonProperty("jouneyDate") @com.fasterxml.jackson.annotation.JsonAlias("journeyDate")
        @NotNull @FutureOrPresent LocalDate journeyDate,
                @NotNull @Min(1) @Max(1000) Integer numberOfSeats,
                @Size(max = 1000) java.util.List<@NotBlank @Pattern(regexp = "[A-Za-z0-9-]{1,32}") String> seatNumbers) {
        public BookingRequest(Long busId, String passengerName, String passengerEmail, String passengerPhone,
                                                  LocalDate journeyDate, Integer numberOfSeats) {
                this(busId, passengerName, passengerEmail, passengerPhone, journeyDate, numberOfSeats, null);
        }
        @AssertTrue(message = "Selected seats must be distinct and match numberOfSeats")
        public boolean isSeatSelectionValid() {
                return seatNumbers == null || (numberOfSeats != null && seatNumbers.size() == numberOfSeats
                                && new java.util.HashSet<>(seatNumbers).size() == seatNumbers.size());
        }
}