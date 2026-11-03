# Seat Management Service

Independent Java 17 / Spring Boot 3.5.7 Maven service on **8084**. Uses Spring MVC,
JPA, H2, Validation, Lombok, DevTools and Springdoc 2.8.13. No shared entities,
databases, discovery server, Feign, broker, container or cloud dependency.

## Run

Start Bus on 8081, then from this directory:

```powershell
$env:INTERNAL_SERVICE_TOKEN='<same-random-token-as-booking-and-payment>'
.\mvnw.cmd test
.\mvnw.cmd spring-boot:run
```

The wrappers reuse the existing Booking Maven wrapper in this monorepo. The runtime
database is `jdbc:h2:file:./data/seatdb;DB_CLOSE_ON_EXIT=FALSE`, username `sa`, with
optional `SEAT_DB_PASSWORD`. Run from this directory so files remain separate.
Tests use isolated memory H2. Existing databases were not deleted or migrated.

Swagger: http://localhost:8084/swagger-ui/index.html. OpenAPI:
http://localhost:8084/v3/api-docs. Gateway base: http://localhost:8080.

## Ownership And Safety

- Seat rows contain id, busId, journeyDate, seatNumber, seatStatus, bookingId,
  reservationExpiryTime, createdAt and updatedAt. Ownership clears on release.
- Database unique constraint: `(bus_id, journey_date, seat_number)`. Indexes support
  bus/date/status, booking ID and expiry queries. Dates have independent inventories.
- Initialization validates the actual Bus DTO, specifically `totalSeates`, and
  generates S1 through S&lt;capacity&gt;. Reinitialization returns 409 and never resets seats.
  Capacity is limited to 1000 per journey. Later Bus capacity edits do not resize inventory.
- A journey row is locked with `PESSIMISTIC_WRITE` for every inventory mutation.
  Reserve checks all selected seats before modifying any. The local transaction
  either commits the whole selection and ledger, or rolls it all back.
- This is database concurrency protection, not an in-memory synchronized block.
  The H2 tests include two racing transactional requests with exactly one winner.
  H2 file mode itself is not a multi-host production database.
- A durable reservation ledger keyed by booking ID records its UUID reference,
  original selection, deadline and terminal state. Same-selection active reserve
  retries return the same reference without extending the deadline. Changed or
  terminal booking IDs cannot be reused. Released tombstones prevent old callbacks
  from touching seats subsequently held by another booking.

## States And Expiry

Seat: `AVAILABLE -> RESERVED -> BOOKED`. Release, expiry or cancellation returns
the seat to AVAILABLE. BOOKED is never released by the expiry worker; use cancellation.
Ledger states: RESERVED, BOOKED, RELEASED, CANCELLED, EXPIRED.

The hold duration defaults to 10 minutes, measured in UTC instants. A worker scans
every 30 seconds; availability reads and mutations also expire overdue holds under
the same journey lock. Confirmation independently checks the exact deadline, so
the scan interval cannot extend a hold. Expired holds cannot be revived. Repeat
confirmation of BOOKED, repeat release of terminal unbooked holds and repeat
cancellation are idempotent. Release of BOOKED returns 409.

| Property | Default | Meaning |
| --- | --- | --- |
| BUS_SERVICE_URL / bus.service.url | http://localhost:8081 | Direct Bus REST lookup |
| INTERNAL_SERVICE_TOKEN | required | Shared nonblank server secret |
| seats.reservation-duration | PT10M | Positive ISO-8601 hold duration |
| seats.expiry-delay-ms | 30000 | Scan delay |
| seats.expiry-enabled | true | Disable only for deterministic tests |
| seats.number-prefix | S | Alphanumeric/hyphen prefix, at most 20 characters |
| services.connect-timeout-ms | 3000 | Bus connect timeout |
| services.read-timeout-ms | 5000 | Bus read timeout |

## All Nine Endpoints

All POST endpoints require `X-Internal-Token`. GET is public for this local demo.
Callbacks optionally accept `X-Reservation-Id`; Booking sends it when available.
Never ship the shared token to React. Use Booking to reserve/cancel customer seats.

| Method | Path | Body / Result |
| --- | --- | --- |
| POST | /api/seats/initialize | busId, journeyDate / 201 seat array |
| GET | /api/seats/bus/{busId}?journeyDate=YYYY-MM-DD | 200 all seats |
| GET | /api/seats/bus/{busId}/available?journeyDate=YYYY-MM-DD | 200 AVAILABLE only |
| GET | /api/seats/{seatId} | 200 seat, 404 if missing |
| POST | /api/seats/reserve | bookingId, busId, journeyDate, selection / 200 reservation |
| POST | /api/seats/confirm/{bookingId} | no body / 200 BOOKED reservation |
| POST | /api/seats/release/{bookingId} | no body / 200 terminal unbooked reservation |
| POST | /api/seats/cancel/{bookingId} | no body / 200 CANCELLED reservation |
| GET | /api/seats/booking/{bookingId} | 200 currently owned seat array, possibly empty |

Initialize body (use a real bus ID and a non-past date):

```json
{"busId":1,"journeyDate":"2027-01-01"}
```

Reserve body:

```json
{"bookingId":42,"busId":1,"journeyDate":"2027-01-01","seatNumbers":["S1","S2"],"numberOfSeats":2}
```

`numberOfSeats` may be omitted with explicit seats. Without seat numbers, a positive
count atomically allocates available seats for legacy Booking clients. Explicit
numbers must be distinct and match the count if present. Unknown numbers are 404;
unavailable, duplicate or conflicting reservations are 409. Validation is 400.
No inventory is initialized implicitly by GET; missing inventory returns 404.

Example reservation response (generated values vary):

```json
{"bookingId":42,"reservationId":"generated-uuid","state":"RESERVED","reservationExpiryTime":"2026-11-03T10:10:00Z","seats":[{"id":1,"busId":1,"journeyDate":"2027-01-01","seatNumber":"S1","seatStatus":"RESERVED","bookingId":42,"reservationExpiryTime":"2026-11-03T10:10:00Z","createdAt":"2026-11-03T10:00:00Z","updatedAt":"2026-11-03T10:00:00Z"}]}
```

The abbreviated example shows one seat; actual responses contain every selected
seat. After release/cancel/expiry, the response seats array is empty because
ownership has cleared. GET by booking is not a reservation-history endpoint;
Booking retains the original selection and reference.

Errors use timestamp/status/message. Invalid downstream DTOs return 502, unavailable
Bus returns 503, missing Bus 404, concurrent/duplicate inventory changes 409,
unauthorized mutation 401 and unexpected errors a generic 500.

## Integration And Recovery

1. Booking looks up fare, persists RESERVING, then calls initialize and reserve.
2. Seat validates Bus independently and holds all seats in one local transaction.
3. Booking records the actual selection and becomes PENDING_PAYMENT. A reserve
   failure attempts cancellation; failed compensation is a durable pending action.
4. Payment checks amount, state, date and expiry. It persists its simulated outcome
   before notifying Booking; only Booking orchestrates Seat confirmation/release.
5. Booking confirms Seat before CONFIRMED; failure releases seats and records
   PAYMENT_FAILED; cancellation releases even BOOKED seats before CANCELLED.
6. Recovery workers retry persisted pending actions/notifications. Stale RESERVING
   drafts are cancelled after 120 seconds. Seat expiry independently bounds abandoned holds.

No distributed transaction or exactly-once delivery is claimed. If a reserve
request completes after a compensating 404, its orphan hold may remain until expiry.
Late SUCCESS after expiry cannot recover seats and needs operator reconciliation;
there is no real charge/refund provider. Booking may still read PENDING_PAYMENT
after its seats expire until the next attempted confirmation/cancellation, so
clients must inspect the expiry and Seat availability. Legacy bookings have no
invented seat assignments. Production requires migrations and a shared production
database for Seat, stronger service/customer auth, monitoring and load tests.

## Postman And Verification

Import [the collection](../scripts/Seat-Booking.postman_collection.json). Run create
bus, create booking, availability, payment and booking read-back; expect RESERVED
then BOOKED and CONFIRMED. Cancel and verify seats return to AVAILABLE. Repeat
with `PAYMENT_SIMULATION_SUCCESS=false`: expect FAILED, PAYMENT_FAILED and no
owned seats. Test duplicate selection against an active hold (409), invalid seat
(404), missing token (401), and repeated notification/cancellation without duplicates.
For standalone Seat calls, use a server-side Postman secret and the POST bodies above.

From the root: `.\scripts\Test-SeatIntegration.ps1` builds and launches temporary
instances with in-memory DBs, runs real HTTP checks and stops only those JVMs.
See [the validation report](../runtime-validation-report.md) for actual executed
test counts, concurrency/expiry coverage and unverified production boundaries.