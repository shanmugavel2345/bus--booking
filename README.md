# Bus Ticket Booking Microservices

Java 17, Spring Boot 3.5.7, Maven, Spring Data JPA, Bean Validation and H2.
Gateway uses Spring Cloud 2025.0.0 and reactive Gateway Server WebFlux; the four
business services use Spring MVC and Springdoc 2.8.13. This is a working local
simulation with production-style boundaries, not a production-ready payment system.

## Inspection And Preserved Behavior

| Module | Port | Base package | Initial finding |
| --- | --- | --- | --- |
| Bus-booking | 8081 | com.shank.busbooking | Existing CRUD, fare and total capacity; Boot 4.1.2 |
| booking-service | 8082 | com.shank.booking_service | Existing booking CRUD/cancel and RestClient lookup; Boot 4.1.2 |
| payment-service/payment_service | 8083 | com.shank.payment_service | Existing PENDING-only payment records; Boot 3.5.7 |
| api-gateway | 8080 | com.shank.api_gateway | Existing three proxy routes; Boot 3.5.7 / Cloud 2025.0.0 |
| seat-management-service | 8084 | com.shank.seat_management_service | New date-specific inventory, atomic holds and expiry |

All four existing applications were retained. Bus and Booking were aligned to
Boot 3.5.7; Boot 4-specific starters, tests and Springdoc were adapted. Bus source
files are physically under `bus_service`, but DTO/entity/service/repository/exception
packages retain their existing shortened names; controller is under `bus_service.controller`.
Legacy endpoint paths and JSON spellings remain accepted. Existing booking totals
are not recalculated, cancellation remains persistent/idempotent, payment lookup
and UUID transaction IDs remain, and each service retains its own database.

Intentional payment behavior change: creation now validates a booking and simulates
an outcome rather than blindly saving a caller-supplied amount as PENDING.
Duplicate non-failed attempts now return 409 instead of creating another record.
No Eureka, Kafka, OpenFeign, cloud services or containers were introduced.

## Architecture And Flow

See the [architecture and component diagrams](.github/modernize/assessment/engines/facts/architecture-diagram.md).

1. Frontend sends public API requests only to Gateway, port 8080.
2. Booking receives bus ID, passenger details, journey date, seat count and optional seat numbers.
3. `BusServiceClient` calls Bus directly using RestTemplate, not through Gateway.
4. Booking validates bus identity and fare, calculates `fare * numberOfSeats` with
  BigDecimal, then saves a RESERVING draft in its own database to obtain an ID.
5. Booking initializes Seat inventory if necessary, then reserves the selected seats
  atomically. Count-only legacy requests automatically allocate available seats.
  Booking stores the returned seats, reservation ID and expiry as PENDING_PAYMENT.
6. Payment receives booking ID and method, looks up Booking directly and validates
  eligibility, journey date and the seat hold's expiry before simulation.
7. Payment ignores any legacy frontend amount, saves PENDING using Booking's amount,
   then saves SUCCESS or FAILED according to server-side simulation configuration.
8. SUCCESS notifies Booking, which confirms Seat before becoming CONFIRMED.
  FAILED notifies Booking to release the hold and remain PAYMENT_FAILED.
9. Cancellation releases reserved or booked seats through Seat. Pending notifications
  and seat actions are persisted and retried by scheduled recovery workers.

The returned Bus DTO includes id, busName, busNumber, source, destination,
`totalSeates` and fare. Total capacity is not available seats. Seat owns a separate
inventory for each bus/date, a persistent reservation ledger and DB-level locks.
IDs are scalar references, never cross-service JPA relationships. The dependency
graph is Payment -> Booking -> Seat -> Bus, plus Booking -> Bus; there is no cycle.
See the [Seat guide](seat-management-service/README.md) for all nine endpoints,
atomicity, expiry, state transitions and detailed request/response examples.

## Start All Five Applications

Use Java 17 and five terminals opened at the repository root. Stop old instances
on the standard ports first. Do not run two processes against the same H2 file.
Set the same random, nonblank `INTERNAL_SERVICE_TOKEN` environment value in the
Booking, Payment and Seat terminals (or their IDE run configurations). Do not commit it.
Startup deliberately fails when that required runtime configuration is absent.

Terminal 1, Bus:

```powershell
Set-Location Bus-booking
.\mvnw.cmd spring-boot:run
```

Terminal 2, Seat:

```powershell
$env:INTERNAL_SERVICE_TOKEN='<same-random-local-token>'
Set-Location seat-management-service
.\mvnw.cmd spring-boot:run
```

Terminal 3, Booking:

```powershell
$env:INTERNAL_SERVICE_TOKEN='<same-random-local-token>'
Set-Location booking-service
.\mvnw.cmd spring-boot:run
```

Terminal 4, Payment:

```powershell
$env:INTERNAL_SERVICE_TOKEN='<same-random-local-token>'
Set-Location payment-service/payment_service
.\mvnw.cmd spring-boot:run
```

Terminal 5, Gateway:

```powershell
.\booking-service\mvnw.cmd -f api-gateway/pom.xml spring-boot:run
```

Wait for each application's `Started` message. Gateway API base URL:
http://localhost:8080. The root path has no homepage; a root 404 is expected.
An empty collection `[]` is a valid response, not a startup error.
For executable JARs, use each module's wrapper `package`, then `java -jar` on
its target artifact. Stop running JARs before `clean` or replacing them on Windows.

H2 files are relative to each service's startup directory. Before using existing
file databases after the Boot/Hibernate/H2 alignment, back them up and validate
schema and H2 file compatibility on a copy. Existing file-data migration was not
tested or performed. Do not delete existing databases to resolve compatibility.
If needed, export with the original H2 version and import into a new database.

## Configuration

| Setting | Default | Owner |
| --- | --- | --- |
| BUS_SERVICE_URL | http://localhost:8081 | Gateway and Booking |
| SEAT_SERVICE_URL | http://localhost:8084 | Gateway and Booking |
| BOOKING_SERVICE_URL | http://localhost:8082 | Gateway and Payment |
| PAYMENT_SERVICE_URL | http://localhost:8083 | Gateway |
| FRONTEND_ORIGIN | http://localhost:3000 | Gateway CORS, one exact origin |
| INTERNAL_SERVICE_TOKEN | Required, no runtime default | Booking, Payment and Seat |
| services.connect-timeout-ms | 3000 | Booking, Payment and Seat |
| services.read-timeout-ms | 5000 | Booking, Payment and Seat |
| PAYMENT_SIMULATION_SUCCESS | true | Payment; false simulates failure |
| seats.reservation-duration | PT10M | Seat; positive ISO-8601 duration |
| seats.expiry-delay-ms | 30000 | Seat; expiry scan interval |
| seats.number-prefix | S | Seat; generated S1, S2, ... |

Bus URL also belongs to Seat. URLs also support `services.bus.url` (Booking),
`bus.service.url` (Seat), `services.booking.url` (Payment) and `seat.service.url` (Booking).
Gateway connection/response limits are 3 seconds / 10 seconds. There are no
automatic retries of creation requests. CORS allows GET, POST, PUT, DELETE,
OPTIONS, Content-Type and Authorization; it does not enable cookie credentials.
Internal tokens are not accepted as frontend CORS headers. CORS is not authentication.

## API Endpoints

Public requests use the following paths on port 8080; direct service ports are
for internal calls, local diagnostics and Swagger, not frontend configuration.

| Method | Path | Successful result |
| --- | --- | --- |
| POST | /api/buses | 201 BusResponse |
| GET | /api/buses | 200 bus array |
| GET | /api/buses/{id} | 200 BusResponse |
| PUT | /api/buses/{id} | 200 updated BusResponse |
| DELETE | /api/buses/{id} | 204 |
| POST | /api/bookings | 201 BookingResponse |
| GET | /api/bookings | 200 booking array |
| GET | /api/bookings/{id} | 200 BookingResponse |
| PUT | /api/bookings/{id}/cancel | 200 CANCELLED booking |
| POST | /api/payments | 201 PaymentResponse and Location header |
| GET | /api/payments | 200 payment array |
| GET | /api/payments/{id} | 200 PaymentResponse |
| GET | /api/payments/booking/{bookingId} | 200 payment array |
| POST | /api/payments/{id}/confirm-booking | 200, retry SUCCESS notification only |
| POST | /api/payments/{id}/notify-booking | 200, retry SUCCESS or FAILED notification |
| GET | /api/seats/bus/{busId}?journeyDate=YYYY-MM-DD | 200 inventory array |
| GET | /api/seats/bus/{busId}/available?journeyDate=YYYY-MM-DD | 200 available array |
| GET | /api/seats/{seatId} | 200 seat |
| GET | /api/seats/booking/{bookingId} | 200 currently owned seats |

Legacy aliases remain: `/api/buses/createBus`, `/api/buses/getAllBus`,
`/api/booking/createBooking`, `/api/booking/getAllBookings`, and singular booking
lookup/cancel paths. Both booking prefixes also accept collection POST/GET.

Internal only: `PUT http://localhost:8082/internal/bookings/{id}/confirm` requires
`X-Internal-Token`. Gateway has no matching route. It returns 401 without a valid
token, 404 for a missing booking, 409 for CANCELLED, or 200 for confirmed bookings.
It cannot receive arbitrary status changes from frontend input.
The matching `/internal/bookings/{id}/payment-failed` endpoint handles failures.
All five Seat POST endpoints also require `X-Internal-Token`; never put this secret
in React. Public users book and cancel through Booking, not Seat mutations.

### Sample JSON

Create bus, `POST /api/buses`:

```json
{"busName":"Express","busNumber":"TN01","source":"Chennai","destination":"Madurai","totalSeates":40,"fare":123.45}
```

Response:

```json
{"id":1,"busName":"Express","busNumber":"TN01","source":"Chennai","destination":"Madurai","totalSeates":40,"fare":123.45}
```

Create booking, `POST /api/bookings` (use a current or future date and a real bus ID):

```json
{"busId":1,"passengerName":"Test Passenger","passengerEmail":"test@example.com","passengerPhone":"1234567890","journeyDate":"2027-01-01","numberOfSeats":3,"seatNumbers":["S1","S2","S3"]}
```

Response:

```json
{"id":1,"busId":1,"passengerName":"Test Passenger","passengerEmail":"test@example.com","passengerPhone":"1234567890","journeyDate":"2027-01-01","numberOfSeats":3,"totalAmount":370.35,"bookingStatus":"PENDING_PAYMENT","seatNumbers":["S1","S2","S3"],"reservationId":"generated-uuid","reservationExpiryTime":"2026-11-03T10:10:00Z"}
```

Legacy input `jouneyDate` remains accepted. Client total/status never control a booking.
Create payment, `POST /api/payments`:

```json
{"bookingId":1,"paymentMethod":"UPI"}
```

Response (IDs, UUID and timestamp vary):

```json
{"id":1,"bookingId":1,"amount":370.35,"paymentMethod":"UPI","paymentStatus":"SUCCESS","transactionId":"14fdbff6-bfc8-4001-9049-1c4a6f07f138","paymentDate":"2026-11-03T14:00:00"}
```

Methods: UPI, CARD, NET_BANKING. Optional legacy `amount` must have valid money
syntax but is ignored. Do not send card numbers, CVV, PINs or banking credentials;
unknown payment fields are rejected.

### Error And Recovery Semantics

400: invalid/malformed request. 404: missing bus, booking or payment.
409: ineligible booking, duplicate non-failed payment, or invalid confirmation.
502: invalid downstream DTO. 503: downstream HTTP/connection failure.
Gateway may return 504 on response timeout. Unexpected errors return generic 500.
Service errors use timestamp/status/error/message, with field details for validation;
Payment also includes path. Gateway unavailable errors use status/error/message.

If notification fails after an outcome was saved, POST returns an error but payment
retains SUCCESS or FAILED with a pending-notification flag. A worker retries every
30 seconds; manual retry is `POST /api/payments/{paymentId}/notify-booking`.
Query `/api/payments/booking/{bookingId}` before acting; never interpret an error as
permission to charge again. FAILED releases seats and requires a new booking/hold.
Booking uses CONFIRMING and CANCEL_PENDING while Seat is unavailable, rather than
claiming success. Expired holds cannot confirm; late SUCCESS needs operator
reconciliation (no real refund provider). PENDING payments after a crash likewise
require reconciliation. See the Seat guide for compensation and recovery limits.

## Swagger And Postman

Swagger UI: http://localhost:8081/swagger-ui/index.html,
http://localhost:8082/swagger-ui/index.html,
http://localhost:8083/swagger-ui/index.html,
http://localhost:8084/swagger-ui/index.html.
Each service exposes `/v3/api-docs`. Gateway does not aggregate Swagger.

Import [the Postman collection](scripts/Seat-Booking.postman_collection.json).
Set `baseUrl=http://localhost:8080`, Content-Type `application/json`,
then create a bus, save its `id`, create a booking using that ID, and submit a
payment using the returned booking ID. GET the booking and expect CONFIRMED.
Import each service's OpenAPI URL to generate requests, then replace request
base URLs with the gateway. Do not send the internal token from the frontend.
To test failure, restart Payment with `PAYMENT_SIMULATION_SUCCESS=false`, create
a fresh booking, pay, and verify FAILED, PAYMENT_FAILED and available seats.

## Verification

From the repository root:

```powershell
foreach ($module in @('Bus-booking','booking-service','payment-service/payment_service','seat-management-service','api-gateway')) {
    & .\booking-service\mvnw.cmd -f "$module/pom.xml" test
    if ($LASTEXITCODE -ne 0) { throw "Tests failed: $module" }
}
.\scripts\Test-SeatIntegration.ps1
```

The Seat integration script builds and starts isolated in-memory instances on
19080-19085, including an extra FAILED-payment simulation instance, and cleans
up its own JVMs. It never opens the existing file databases. Use `-BasePort` to
change the range. The older `Test-ServiceIntegration.ps1` requires running
services and creates records: run it only against disposable databases.
See [runtime-validation-report.md](runtime-validation-report.md) for executed results.

## Production Boundaries

This is not production certification. Customer authentication, real payments and
refunds, cross-replica payment idempotency, migrations, monitoring and load testing
remain outstanding. H2 file mode is not a multi-host database. Local retry workers
provide eventual recovery, not an atomic transaction across services. No AWS,
RDS, Docker, Kafka, Eureka or OpenFeign configuration was added or executed.

## Important Classes And Changed Files

All Java paths below are relative to each module's existing `src/main/java` tree.
Shared layers retain constructor injection; repositories only access their own entities.

| Module | New files | Responsibility |
| --- | --- | --- |
| Gateway | GatewayErrorHandler, GatewayRequestLogging | Safe 503 responses; method/path/status logging |
| Booking | config/RestTemplateConfiguration | Configurable bounded synchronous HTTP client |
| Booking | service/BusServiceClient, dto/BusResponse, exception/BusServiceException | Bus contract and safe HTTP failure translation |
| Booking | controller/InternalBookingController | Token-protected idempotent confirmation |
| Payment | config/RestTemplateConfiguration | Configurable bounded synchronous HTTP client |
| Payment | service/BookingServiceClient, dto/BookingResponse, exception/BookingServiceException | Booking lookup and internal confirmation |

Modified: Bus and Booking Maven files; BusController aliases; BookingController
aliases, BookingRequest validation/date alias, BookingService pricing/confirmation,
Booking entity status operation, BookingRepository locked lookup, booking error
handling; PaymentService orchestration/retry, PaymentRequest optional legacy amount,
Payment entity/status transitions, PaymentController retry, payment error handling;
runtime/test properties; gateway routing/CORS; all three existing module READMEs.
The old Booking `BusClient` was replaced, not duplicated.

New tests: BusApiTests, BookingServiceClientTests, GatewayErrorHandlerTests;
new Bus test properties and the live integration script. Existing BookingApiTests,
BusClientTests, PaymentApiTests and ApiGatewayApplicationTests were extended.
New documentation: this guide, runtime report, architecture document.
Unrelated pre-existing IDE and database changes were not reverted or rewritten.

## Folder Structure

```text
Bus-booking/
  pom.xml
  src/main/java/com/shank/busbooking/
    BusBookingApplication.java
    bus_service/{controller,dto,entity,exception,repository,service}/
  src/main/resources/application.properties
  src/test/{java,resources}/
booking-service/
  pom.xml
  src/main/java/com/shank/booking_service/
    BookingServiceApplication.java
    config/RestTemplateConfiguration.java
    controller/{BookingController,InternalBookingController}.java
    dto/{BookingRequest,BookingResponse,BusResponse}.java
    entity/{Booking,BookingStatus}.java
    exception/{BookingNotFoundException,BusServiceException,GlobalExceptionHandler}.java
    repository/BookingRepository.java
    service/{BookingService,BusServiceClient}.java
  src/{main,test}/resources/application.properties
  src/test/java/com/shank/booking_service/
payment-service/payment_service/
  pom.xml
  src/main/java/com/shank/payment_service/
    PaymentServiceApplication.java
    config/RestTemplateConfiguration.java
    controller/PaymentController.java
    dto/{PaymentRequest,PaymentResponse,BookingResponse}.java
    entity/{Payment,PaymentStatus,PaymentMethod}.java
    exception/{PaymentNotFoundException,BookingServiceException,GlobalExceptionHandler}.java
    repository/PaymentRepository.java
    service/{PaymentService,BookingServiceClient}.java
  src/{main,test}/resources/application.properties
  src/test/java/com/shank/payment_service/
api-gateway/
  pom.xml
  src/main/java/com/shank/api_gateway/
    ApiGatewayApplication.java
    GatewayErrorHandler.java
    GatewayRequestLogging.java
  src/main/resources/application.properties
  src/test/java/com/shank/api_gateway/
scripts/Test-ServiceIntegration.ps1
runtime-validation-report.md
.github/modernize/assessment/engines/facts/architecture-diagram.md
```

## Production Boundaries

Customer authentication, ownership checks, TLS and network isolation are not
implemented. Restrict backend ports and internal endpoints at deployment; a
shared token is only a minimal service-to-service guard. Gateway as the frontend
entry point is a routing convention until those deployment restrictions exist.
Disable public H2/Swagger/SQL logging and replace ddl-auto with migrations.

Payment duplicate detection uses synchronized creation within one JVM, not
distributed idempotency or a database-wide payment claim. Before multiple replicas,
add persisted idempotency/uniqueness and recovery. No database transaction spans
services; SUCCESS plus unconfirmed booking needs reconciliation. Cancellation
can race payment and requires compensation/refund policy in a real system.
No seat reservations, overselling protection, booking expiry, refunds, verified
provider callbacks, outbox, broker, discovery or deployment platform are included.