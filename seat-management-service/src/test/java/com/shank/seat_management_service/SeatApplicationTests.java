package com.shank.seat_management_service;

import com.shank.seat_management_service.dto.SeatContracts.*;
import com.shank.seat_management_service.entity.*;
import com.shank.seat_management_service.repository.*;
import com.shank.seat_management_service.service.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class SeatApplicationTests {
    @Autowired private SeatService service;
    @Autowired private SeatRepository seats;
    @Autowired private ReservationRepository reservations;
    @Autowired private JourneyInventoryRepository inventories;
    @Autowired private MockMvc mvc;
    @MockitoBean private BusServiceClient busClient;
    @MockitoBean private Clock clock;
    private final LocalDate date = LocalDate.now().plusDays(5);
    private final Instant now = Instant.parse("2026-11-03T10:00:00Z");

    @BeforeEach
    void setup() {
        seats.deleteAll();
        reservations.deleteAll();
        inventories.deleteAll();
        when(clock.instant()).thenReturn(now);
        when(busClient.findById(12L)).thenReturn(new BusResponse(12L, "Express", "TN01", "A", "B", 4));
    }

    private void initialize() { service.initialize(new InitializeRequest(12L, date)); }
    private ReserveRequest request(long bookingId, String... numbers) {
        return new ReserveRequest(bookingId, 12L, date, List.of(numbers));
    }

    @Test
    void initializesActualCapacityAndRejectsDuplicate() {
        initialize();
        assertThat(service.list(12L, date, true)).hasSize(4).extracting(SeatResponse::seatNumber)
                .containsExactly("S1", "S2", "S3", "S4");
        assertThatThrownBy(this::initialize).isInstanceOf(ResponseStatusException.class);
        assertThat(seats.count()).isEqualTo(4);
        service.initialize(new InitializeRequest(12L, date.plusDays(1)));
        assertThat(seats.count()).isEqualTo(8);
    }

    @Test
    void reservesMultipleSeatsAndRetriesWithoutExtendingExpiry() {
        initialize();
        var first = service.reserve(request(20L, "S2", "S1"));
        var retry = service.reserve(request(20L, "S1", "S2"));
        assertThat(retry.reservationId()).isEqualTo(first.reservationId());
        assertThat(first.reservationExpiryTime()).isEqualTo(now.plusSeconds(600));
        assertThat(first.seats()).hasSize(2).allMatch(seat -> seat.seatStatus() == SeatStatus.RESERVED);
        assertThat(service.list(12L, date, true)).hasSize(2);
        assertThat(reservations.count()).isEqualTo(1);
    }

    @Test
    void conflictingSelectionRollsBackEntireReservation() {
        initialize();
        service.reserve(request(20L, "S2"));
        assertThatThrownBy(() -> service.reserve(request(21L, "S1", "S2"))).isInstanceOf(ResponseStatusException.class);
        assertThat(service.list(12L, date, true)).extracting(SeatResponse::seatNumber).contains("S1");
        assertThat(reservations.existsById(21L)).isFalse();
        assertThat(service.forBooking(21L)).isEmpty();
    }

    @Test
    void rejectsUnknownAndDuplicateSeats() {
        initialize();
        assertThatThrownBy(() -> service.reserve(request(20L, "S99"))).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.reserve(request(20L, "S1", "S1"))).isInstanceOf(ResponseStatusException.class);
        assertThat(service.list(12L, date, true)).hasSize(4);
    }

    @Test
    void concurrentRequestsHaveExactlyOneWinner() throws Exception {
        initialize();
        var executor = Executors.newFixedThreadPool(2);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (long bookingId : List.of(20L, 21L)) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) { throw new IllegalStateException("Start barrier failed"); }
                    try { service.reserve(request(bookingId, "S1")); return true; }
                    catch (ResponseStatusException failure) {
                        assertThat(failure.getStatusCode().value()).isEqualTo(409);
                        return false;
                    }
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            int winners = 0;
            for (var result : results) { if (result.get(10, TimeUnit.SECONDS)) { winners++; } }
            assertThat(winners).isEqualTo(1);
            assertThat(service.list(12L, date, true)).hasSize(3);
            assertThat(reservations.count()).isEqualTo(1);
        } finally { executor.shutdownNow(); }
    }

    @Test
    void confirmsIdempotentlyAndExpiryNeverReleasesBookedSeats() {
        initialize();
        var reservation = service.reserve(request(20L, "S1"));
        service.change(20L, reservation.reservationId(), "confirm");
        service.change(20L, reservation.reservationId(), "confirm");
        when(clock.instant()).thenReturn(now.plusSeconds(601));
        service.expire(20L);
        assertThat(service.forBooking(20L)).singleElement().extracting(SeatResponse::seatStatus).isEqualTo(SeatStatus.BOOKED);
        assertThatThrownBy(() -> service.change(20L, reservation.reservationId(), "release")).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void expiresOnlyReservedSeatsAndRejectsLateConfirmation() {
        initialize();
        var reservation = service.reserve(request(20L, "S1", "S2"));
        when(clock.instant()).thenReturn(now.plusSeconds(600));
        assertThat(reservations.expired(clock.instant())).containsExactly(20L);
        service.expire(20L);
        assertThat(service.list(12L, date, true)).hasSize(4);
        assertThat(reservations.findById(20L).orElseThrow().getState()).isEqualTo(SeatReservation.State.EXPIRED);
        assertThatThrownBy(() -> service.change(20L, reservation.reservationId(), "confirm")).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void releaseAndCancellationAreIdempotentWithoutAffectingNewOwner() {
        initialize();
        var first = service.reserve(request(20L, "S1"));
        service.change(20L, first.reservationId(), "release");
        service.reserve(request(21L, "S1"));
        service.change(20L, first.reservationId(), "release");
        service.change(20L, first.reservationId(), "cancel");
        assertThat(service.forBooking(21L)).hasSize(1);
        assertThatThrownBy(() -> service.change(20L, first.reservationId(), "confirm")).isInstanceOf(ResponseStatusException.class);
        var second = reservations.findById(21L).orElseThrow();
        service.change(21L, second.getReservationId(), "confirm");
        service.change(21L, second.getReservationId(), "cancel");
        service.change(21L, second.getReservationId(), "cancel");
        assertThat(service.list(12L, date, true)).hasSize(4);
    }

    @Test
    void busOutageNeverInitializesInventory() {
        when(busClient.findById(12L)).thenThrow(new com.shank.seat_management_service.exception.SeatException(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "Bus service is unavailable"));
        assertThatThrownBy(this::initialize).isInstanceOf(ResponseStatusException.class);
        assertThat(inventories.count()).isZero();
        assertThat(seats.count()).isZero();
    }

    @Test
    void validatesAuthorizationAndRequestBody() throws Exception {
        mvc.perform(post("/api/seats/initialize").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/seats/initialize").header("X-Internal-Token", "test-internal-token")
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/seats/bus/12?journeyDate=bad")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/seats/bus/12/available")).andExpect(status().isBadRequest());
    }

    @Test
    void exposesAllNineRoutesAndSwagger() throws Exception {
        String header = "test-internal-token";
        mvc.perform(post("/api/seats/initialize").header("X-Internal-Token", header)
                .contentType(MediaType.APPLICATION_JSON).content("{\"busId\":12,\"journeyDate\":\"" + date + "\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.length()").value(4));
        mvc.perform(get("/api/seats/bus/12?journeyDate=" + date)).andExpect(status().isOk());
        mvc.perform(get("/api/seats/bus/12/available?journeyDate=" + date)).andExpect(status().isOk());
        Long seatId = seats.findAll().get(0).getId();
        mvc.perform(get("/api/seats/" + seatId)).andExpect(status().isOk());
        mvc.perform(post("/api/seats/reserve").header("X-Internal-Token", header).contentType(MediaType.APPLICATION_JSON)
                .content("{\"bookingId\":20,\"busId\":12,\"journeyDate\":\"" + date + "\",\"seatNumbers\":[\"S1\"]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("RESERVED"));
        mvc.perform(get("/api/seats/booking/20")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(post("/api/seats/confirm/20").header("X-Internal-Token", header)).andExpect(status().isOk());
        mvc.perform(post("/api/seats/cancel/20").header("X-Internal-Token", header)).andExpect(status().isOk());
        service.reserve(request(21L, "S1"));
        mvc.perform(post("/api/seats/release/21").header("X-Internal-Token", header)).andExpect(status().isOk());
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    }

    @Test
    void automaticAllocationUsesOnlyAvailableSeatsAndIsIdempotent() {
        initialize();
        service.reserve(request(20L, "S1"));
        var automatic = new ReserveRequest(21L, 12L, date, null, 2);
        var reserved = service.reserve(automatic);
        assertThat(reserved.seats()).extracting(SeatResponse::seatNumber).containsExactly("S2", "S3");
        assertThat(service.reserve(automatic).reservationId()).isEqualTo(reserved.reservationId());
        assertThatThrownBy(() -> service.reserve(new ReserveRequest(22L, 12L, date, null, 2)))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void expiryWorkerIsRepeatableAndNeverReleasesBookedInventory() {
        initialize();
        service.reserve(request(20L, "S1"));
        var booked = service.reserve(request(21L, "S2"));
        service.change(21L, booked.reservationId(), "confirm");
        when(clock.instant()).thenReturn(now.plusSeconds(601));
        var worker = new ReservationExpiryService(reservations, service, clock);
        worker.expire();
        worker.expire();
        assertThat(reservations.findById(20L).orElseThrow().getState()).isEqualTo(SeatReservation.State.EXPIRED);
        assertThat(service.forBooking(21L)).singleElement().extracting(SeatResponse::seatStatus).isEqualTo(SeatStatus.BOOKED);
        assertThat(service.list(12L, date, true)).hasSize(3);
    }

    @Test
    void databaseRejectsDuplicateBusDateSeatEvenOutsideService() {
        initialize();
        assertThatThrownBy(() -> seats.saveAndFlush(new Seat(12L, date, "S1", now)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(service.list(12L, date, true)).hasSize(4);
    }

    @Test
    void contextLoads() {
    }
}