# Bus Booking Service

Java 17 REST service using Spring Boot 3.5.7, Spring Data JPA,
H2, Bean Validation and Springdoc 2.8.13. All application packages live under
`com.shank.booking_service`. Controllers use DTOs; transactional services own pricing
and cancellation. Inventory belongs to the separate Seat service; customer authentication is not included.
Payment Service can confirm bookings through a token-protected internal endpoint.

## Run On Windows

From this directory (the directory containing `pom.xml` and `mvnw.cmd`):

Set `INTERNAL_SERVICE_TOKEN` in Booking, Payment and Seat to the same nonblank value
before starting. There is deliberately no built-in runtime token. Tests configure
their own non-secret token. See the [integration guide](../README.md).

```powershell
.\mvnw.cmd test
.\mvnw.cmd clean package
java -jar target\booking-service2-0.0.1-SNAPSHOT.jar
```

Alternatively run ` .\mvnw.cmd spring-boot:run` for development.
The service listens at http://localhost:8082.

- Swagger UI: http://localhost:8082/swagger-ui.html
- OpenAPI JSON: http://localhost:8082/v3/api-docs
- H2 console: http://localhost:8082/h2-console
- JDBC URL: `jdbc:h2:file:./data/busdb;MODE=PostgreSQL`
- Driver: `org.h2.Driver`, username: `sa`, password: empty

The file database is relative to the startup directory and persists across restarts.
These settings and the enabled H2 console are for local development only.
Tests use a separate in-memory database with schema recreation, never `data/busdb`.
Booking creation calls Bus Service directly using Spring RestTemplate:
`GET /api/buses/{id}`. The price is the returned fare multiplied by seat count.
Start Bus Service on port 8081, or override `BUS_SERVICE_URL` / `services.bus.url`.
The client uses a 3-second connection timeout and a 5-second read timeout without
automatic retries. A missing bus returns 404; service failures return 503;
invalid bus details return 502. These failures do not save a booking.
Existing bookings keep their original price even if the bus fare changes later.
Start Seat on 8084 or set `SEAT_SERVICE_URL` / `seat.service.url`.
Booking persists a RESERVING draft, initializes inventory as needed and reserves
selected seats before returning PENDING_PAYMENT. Omitted seat numbers trigger
atomic automatic allocation; total capacity is never treated as remaining inventory.
Failed reservation compensates through Seat and retains a cancelled audit record.

## Requests

PowerShell examples below use today's date so validation remains valid.
Both `jouneyDate` and `journeyDate` are accepted; responses use `journeyDate`.
RESTful aliases `POST /api/bookings`, `GET /api/bookings`,
`GET /api/bookings/{id}` and `PUT /api/bookings/{id}/cancel` are available.

### Create: POST /api/booking/createBooking

```powershell
$body = @{
    busId = 12
    passengerName = 'Test Passenger'
    passengerEmail = 'test@example.com'
    passengerPhone = '1234567890'
    jouneyDate = (Get-Date -Format 'yyyy-MM-dd')
    numberOfSeats = 2
    seatNumbers = @('S1', 'S2')
} | ConvertTo-Json
$booking = Invoke-RestMethod -Method Post -Uri 'http://localhost:8082/api/booking/createBooking' -ContentType 'application/json' -Body $body
$booking
```

Returns HTTP 201 and all booking fields, including a generated `id`, server-calculated
`totalAmount` (1000.00 for two seats when the bus fare is 500.00), `bookingStatus` of
`PENDING_PAYMENT`, `seatNumbers`, `reservationId` and `reservationExpiryTime`.
Client-supplied price and status do not control the booking.

### List: GET /api/booking/getAllBookings

```powershell
Invoke-RestMethod 'http://localhost:8082/api/booking/getAllBookings'
```

Returns HTTP 200 with a JSON array, or `[]` if no bookings exist.

### Lookup: GET /api/booking/{id}

```powershell
Invoke-RestMethod "http://localhost:8082/api/booking/$($booking.id)"
```

### Cancel: PUT /api/booking/{id}/cancel

```powershell
Invoke-RestMethod -Method Put -Uri "http://localhost:8082/api/booking/$($booking.id)/cancel"
```

Lookup and cancellation return HTTP 200 with a booking, or HTTP 404 when missing.
Cancellation persists `CANCELLED` without deletion and can be repeated successfully.
Seat must acknowledge release/cancellation first; outages retain CANCEL_PENDING
and a durable action for the 30-second recovery worker. Confirmation similarly
uses CONFIRMING until Seat acknowledges BOOKED. Local DB transactions do not span HTTP.
`PUT /internal/bookings/{id}/confirm` requires `X-Internal-Token` and is intended
only for Payment Service. It is idempotent and rejects cancelled bookings with
409. It is not exposed through the gateway. Cancellation does not issue refunds.
`PUT /internal/bookings/{id}/payment-failed` uses the same token and releases the hold.
Stale RESERVING drafts are cancelled after a fixed 120-second timeout. Configure
`booking.seat-recovery-enabled` and `booking.seat-recovery-delay-ms` for the worker.
Legacy bookings without selected
seats retain their previous behavior; their inventory is not fabricated/backfilled.
See the [Seat guide](../seat-management-service/README.md) for recovery limitations.

## Errors And Verification

Errors include `timestamp`, `status`, `error` and `message`. Validation errors are
HTTP 400 with message `Validation failed` and a `details` field-to-message map.
Malformed JSON, invalid dates and invalid path IDs return HTTP 400 with message
`Malformed request`. Missing IDs return `Booking not found with id: {id}`.
Unexpected failures return HTTP 500 with `Unexpected error occurred`, without stack traces.

The Maven test suite exercises creation, persisted price/status, response spelling,
empty/populated lists, lookup, missing IDs, persistent repeated cancellation,
required/invalid fields and malformed bodies/path variables against isolated H2.