package com.shank.seat_management_service.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.shank.seat_management_service.entity.*;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.List;

public final class SeatContracts {
    private SeatContracts() { }

    public record InitializeRequest(@NotNull @Positive Long busId,
                                    @NotNull @FutureOrPresent LocalDate journeyDate) { }
    public record ReserveRequest(@NotNull @Positive Long bookingId, @NotNull @Positive Long busId,
                                 @NotNull @FutureOrPresent LocalDate journeyDate,
                                 @Size(max = 1000) List<@NotBlank @Pattern(regexp = "[A-Za-z0-9-]{1,32}") String> seatNumbers,
                                 @Min(1) @Max(1000) Integer numberOfSeats) {
        public ReserveRequest(Long bookingId, Long busId, LocalDate journeyDate, List<String> seatNumbers) {
            this(bookingId, busId, journeyDate, seatNumbers, seatNumbers == null ? null : seatNumbers.size());
        }
        @AssertTrue(message = "Provide seats or a positive seat count; supplied counts must match")
        public boolean isSelectionValid() {
            return seatNumbers == null || seatNumbers.isEmpty()
                    ? numberOfSeats != null && numberOfSeats > 0
                    : numberOfSeats == null || numberOfSeats == seatNumbers.size();
        }
    }
    public record SeatResponse(Long id, Long busId, LocalDate journeyDate, String seatNumber,
                               SeatStatus seatStatus, Long bookingId, Instant reservationExpiryTime,
                               Instant createdAt, Instant updatedAt) {
        public static SeatResponse from(Seat seat) {
            return new SeatResponse(seat.getId(), seat.getBusId(), seat.getJourneyDate(), seat.getSeatNumber(),
                    seat.getSeatStatus(), seat.getBookingId(), seat.getReservationExpiryTime(), seat.getCreatedAt(), seat.getUpdatedAt());
        }
    }
    public record ReservationResponse(Long bookingId, String reservationId, String state,
                                      Instant reservationExpiryTime, List<SeatResponse> seats) { }
    public record ReservationLocation(Long busId, LocalDate journeyDate) { }
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BusResponse(Long id, String busName, String busNumber, String source,
                              String destination, Integer totalSeates) { }
}