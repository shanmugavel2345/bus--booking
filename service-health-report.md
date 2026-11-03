# Service Health Report

## Current Repair And Verification Results

Verified: 2026-11-03, final snapshot 16:28:13, Windows local time (UTC+05:30).
This section supersedes the historical read-only check below. Repairs and owned
synthetic workflow records were authorized for this run.

**All five services are running simultaneously on their original ports. All four
Gateway route families and both complete payment outcomes passed live testing.**

| Service | Port | Final PID | Actual HTTP Evidence | Result |
| --- | --- | --- | --- | --- |
| API Gateway | 8080 | 32212 | All four backend route families return 200 | PASS |
| Bus | 8081 | 32296 | GET /api/buses direct and through Gateway: 200 | PASS |
| Booking | 8082 | 23644 | GET /api/bookings direct and through Gateway: 200 | PASS |
| Payment | 8083 | 27548 | GET /api/payments direct and through Gateway: 200 | PASS |
| Seat Management | 8084 | 18664 | GET /api/seats/booking/35 direct and through Gateway: 200 | PASS |

Gateway GET /api/buses/getAllBus and /api/booking/getAllBookings also return 200.
Health here means verified business endpoints and flows, not Actuator health.

### Causes And Repairs

1. Booking was absent at the earlier snapshot. It was started with the required
  internal-service token. Its original termination cause remains unproven.
  After startup, the real recovery worker reported SQLState 22030 because the
  existing booking_status native H2 enum allowed only CANCELLED, CONFIRMED and
  PENDING, rejecting RESERVING. Added explicit VARCHAR mapping and an idempotent
  post-Hibernate SQL migration. This preserves existing rows and enables the
  expanded lifecycle. The original Booking record was compared before/after
  migration and preserved. Do not conflate this observed worker defect with an
  established explanation for the earlier process termination.
2. Bus source already exposed the collection GET. The old running artifact did
  not. Rebuilt/tested and refreshed Bus against its existing file database;
  collection and legacy paths now work. No Bus API source change was needed.
3. Gateway source already included both Booking aliases and Seat routing. Its
  older packaged/running configuration did not. Rebuilt/tested and restarted
  with current routes. The old singular Booking 500 was connection refusal to
  the absent backend; the plural Booking and Seat 404s were missing old routes.
  No path rewrite, method filter, port or Gateway source change was needed.
4. Payment was initially left untouched. Live POST /api/payments without amount
  returned 400 with details.amount="must not be null", unlike the current DTO.
  Supplying the exact Booking fare created only a legacy PENDING payment.
  After testing and refreshing Payment, a genuine persistence failure emerged:
  SQLState 22030, "Value not permitted for column ('PENDING'): SUCCESS".
  Added VARCHAR status mapping and the corresponding data-preserving migration,
  with a regression reproducing the PENDING-only enum. Payment source changes
  were limited to this proven compatibility defect and its test/configuration.

Both migrations run after Hibernate initialization, keep status names unchanged,
and neither drop tables nor reset identities. Closed database backups were taken
before migration/runtime refresh, beside the original databases with names
containing before-status-migration or before-runtime-refresh. The legacy payment
transaction c99a9755-eeb4-4f6d-92af-3e54802798bd survived migration unchanged.
Diagnostic bookings 33 and 34 were cancelled and their holds released; their
PENDING payment audit records were retained rather than deleted or reprocessed.

### Actual Gateway Workflow Results

Fixture: Bus 65, number RUNTIME-20261103-162037, journey 2026-11-10,
four seats, fare 123.45 per seat. No original user records were deleted.

| Step | Actual Result |
| --- | --- |
| Bus retrieval | Created fixture retrieved through /api/buses/65 |
| Seat initialization and availability | Four seats initialized and available |
| Reservation | S1/S2 RESERVED; availability fell to two |
| Reservation retry | Same reservation reference retained |
| Booking creation | Seat-backed booking returned PENDING_PAYMENT |
| Payment success | Payment 65: SUCCESS, amount 246.90 derived from Booking |
| Booking confirmation | Booking 35: CONFIRMED through actual Payment callback |
| Seat confirmation | Booking 35 owns two BOOKED seats, S1/S2 |
| Confirmation retry | POST /api/payments/65/confirm-booking succeeded idempotently |
| Payment failure | Payment 97: FAILED using existing simulation configuration |
| Failure callback | Booking 36: PAYMENT_FAILED |
| Seat release | Exactly S3/S4 became AVAILABLE; S1/S2 stayed BOOKED |
| Failure retry | POST /api/payments/97/notify-booking succeeded idempotently |
| Persistence | SUCCESS and FAILED records retained after Payment restart |

Failure simulation temporarily used payments.simulation.success=false on 8083,
then Payment was restored without that override. No ports changed. One retry
probe incorrectly used PUT and received 405; the controller's actual POST method
was then tested successfully. This was a test-method error, not a Gateway defect.

### Build And Test Evidence

- Booking: 52 tests passed, including legacy enum migration coverage.
- Bus: 3 tests passed.
- Gateway: 21 tests passed.
- Payment: 44 tests passed, including the new enum migration regression.
- Total affected-service tests: 120, zero failures/errors.
- Seat's earlier 19-test result is historical; that unchanged suite was not rerun.
- 28 explicit live assertions passed across diagnostics and repaired workflows.
  The earlier 400, enum-related 500 and incorrect-method 405 are disclosed above,
  not counted as successful business operations.
- All four affected executable JARs were repackaged after the passing tests,
  preventing future launches from reusing their obsolete packaged contracts.
- Edited Java files have no editor diagnostics; scoped git diff --check passed.

### Runtime Boundaries

Current servers run tested target/classes with Java 17 and devtools restart
disabled. Booking, Seat and Payment share the explicit local verification token;
no production fallback credential was introduced. Restarting outside this session
still requires the documented INTERNAL_SERVICE_TOKEN environment variable.
Bus, Booking and Payment retain their original module-relative file databases.
Seat remains the pre-existing diagnostic instance, using its dedicated file
database under the user's temporary bus-booking-seat-diagnostic-20261103 directory,
not the module's default database. This run does not certify that default Seat
database, long-duration uptime, or production payment processing.

The previously observed Seat response of 500 for an unconfigured Actuator path
remains outside this repair; no Actuator dependency or synthetic health endpoint
was added. Business endpoints and actual callbacks were used for verification.

## Historical Read-Only Check

The remainder records the earlier state before the repairs above; it is retained
as diagnostic history, not the current health verdict.

Checked: 2026-11-03, 16:01:32-16:04:22, Windows local time (UTC+05:30).
Scope: read-only verification of the existing applications on ports 8080-8084.

**All five services are NOT running simultaneously.** Gateway, Bus, Payment and
Seat were present in both process/port snapshots. Booking was absent in both.
**The current system is NOT ready for full integration testing.**

## Health Table

Statuses describe the observed endpoints, not full business-workflow certification.

| Service | Port | Process Running | Port Listening | HTTP Response | Gateway Routing | Status |
| --- | --- | --- | --- | --- | --- | --- |
| API Gateway | 8080 | Yes, PID 32104 | Yes, expected Java process | Payment and legacy Bus proxies 200; Actuator 404 | Bus legacy and Payment work; Booking and Seat fail | GATEWAY ROUTING FAILED |
| Bus Service | 8081 | Yes, PID 36456 | Yes, expected Java process | /api/buses 404; /api/buses/getAllBus 200 | Same results through Gateway | RUNNING BUT API FAILED |
| Booking Service | 8082 | No matching application process | No | Unable to connect to the remote server | /api/bookings 404; /api/booking 500 | NOT RUNNING |
| Payment Service | 8083 | Yes, PID 16204 | Yes, expected Java process | /api/payments 200; Actuator 404 | /api/payments 200 | HEALTHY |
| Seat Management | 8084 | Yes, PID 18664 | Yes, expected Java process | /api/seats/booking/1 200; unconfigured Actuator path 500 | /api/seats/booking/1 404 | GATEWAY ROUTING FAILED |

Payment's HEALTHY status is limited to process identity, listener, successful
database-backed list response and matching Gateway response. No payment creation,
booking confirmation or cross-service workflow was tested. Seat's business lookup
succeeded; its status reflects failed Gateway access, not a failed database lookup.

## Processes, Port Ownership And Duplicates

Snapshots at 16:01:32 and 16:03:24 found the same owners:

| Service | Process Identity | Instance Count |
| --- | --- | --- |
| Gateway | java.exe, api-gateway-0.0.1-SNAPSHOT.jar, PID 32104 | 1 |
| Bus | java.exe, Bus-booking-0.0.1-SNAPSHOT.jar, PID 36456 | 1 |
| Booking | No matching JAR or application main class | 0 |
| Payment | java.exe, payment_service-0.0.1-SNAPSHOT.jar, PID 16204 | 1 |
| Seat | com.shank.seat_management_service.SeatManagementServiceApplication, PID 18664 | 1 |

No unrelated application occupied an expected port. No duplicate instances of
these services were identified. Other Java processes were three Eclipse Java
language servers and org.jetbrains.idea.maven.server.RemoteMavenServer36, not
additional service instances. Process arguments were inspected selectively;
credential/token arguments were not printed or recorded.

PowerShell checks included Get-NetTCPConnection -State Listen and
Get-CimInstance Win32_Process, correlating each listener's OwningProcess with its
Java main class or -jar argument. Counts are distinct PIDs, not IPv4/IPv6 listeners.

## HTTP And Gateway Evidence

Only GET requests were sent, with five-second HTTP timeouts. Existing controller
mappings and running OpenAPI documents supplied the API paths. No API records
were created, updated or deleted. The Seat lookup is annotated readOnly=true;
inventory-list endpoints were deliberately avoided because they can expire holds.

| GET Path | Direct Backend | Through 8080 | Interpretation |
| --- | --- | --- | --- |
| /api/buses | 8081: 404 | 404 | Current collection endpoint missing from running Bus contract |
| /api/buses/getAllBus | 8081: 200, [] | 200, [] | Existing legacy Bus route works; response bodies match |
| /api/bookings | 8082: no HTTP response | 404, empty body | Booking stopped; plural Gateway route not exposed by packaged defaults |
| /api/booking | Not separately probed directly | 500, empty body | Packaged singular route exists; stopped backend is a likely cause |
| /api/payments | 8083: 200, [] | 200, [] | Payment route works; response bodies match |
| /api/seats/booking/1 | 8084: 200, [] | 404, empty body | Seat API responds directly but Gateway access fails |

An empty list verifies that the endpoint executes successfully, not that a
reservation/payment workflow or representative persisted records are correct.

### Actuator Limitation

No Actuator dependency/configuration or custom health endpoint was found in the
checked source manifests/properties. Nothing was added or enabled.

| GET /actuator/health | Observed Result |
| --- | --- |
| 8080 | 404, empty body |
| 8081 | 404, Not Found |
| 8082 | No HTTP response: Unable to connect to the remote server |
| 8083 | 404, Not Found |
| 8084 | 500, message: Unexpected error occurred |

Seat's log at 16:02:31 identifies the exact cause of that 500:
`org.springframework.web.servlet.resource.NoResourceFoundException: No static resource actuator/health.`
Its GlobalExceptionHandler catches Exception and returns 500, converting this
missing resource into a generic server error. This is not evidence of a failed
configured Actuator health check or database outage. The existing API lookup
and OpenAPI are the available readiness evidence; component health remains limited.

### Runtime Contract Differences

Running Bus OpenAPI exposes only /api/buses/{id}, /api/buses/createBus and
/api/buses/getAllBus. Current Bus source also maps GET/POST /api/buses.
Running Payment OpenAPI exposes collection, ID and booking lookup endpoints,
but not the retry endpoints present in current source. Their loaded contracts
therefore differ from the current source; no artifact was rebuilt or restarted.

The existing Gateway JAR's BOOT-INF/classes/application.properties was read using
ZipFile without changing it. Its defaults contain Bus /api/buses/**, Booking
**/api/booking/** only, and Payment /api/payments/**. There is **no Seat route**.
Current source properties include the plural Booking alias and Seat route.
These packaged differences are consistent with the observed 404 responses.
Runtime configuration overrides were not inspected, so packaged defaults alone
are not proof of the complete effective configuration. No age inference was made
from the JAR's reproducible-build timestamp.

## Startup Logs And Errors

Current Seat evidence is in
seat-management-service/target/seat-startup-diagnostic.log and matches PID 18664:

- Hikari connected successfully to the dedicated diagnostic H2 file at
  C:/Users/2564626/AppData/Local/Temp/bus-booking-seat-diagnostic-20261103/seatdb.
- Tomcat started on 8084; Started SeatManagementServiceApplication in 7.938 seconds.
- The missing Actuator resource error above was logged during this check.
- No startup bean-creation failure, port conflict or database connection failure
  was found in this current-process log. Seat uses a diagnostic database; this
  does not certify the normal production/default Seat database or its migration.

Available other logs are historical isolated-port logs, not current standard-port
console captures:

- Bus-booking/target/seat-e2e-19081.log: PID 34516, startup truncated during Hibernate
  initialization. No exception/termination cause is recorded in this file.
- booking-service/target/seat-e2e-19082.log: PID 12184, Hikari and startup succeeded
  on 19082. It does not demonstrate that Booking is currently running on 8082.
- payment-service/payment_service/target/seat-e2e-19083.log: PID 24380, startup stops
  at HikariPool-1 - Starting. Empty stderr; no exact database failure is recorded.
- api-gateway/target/seat-e2e-19080.log: PID 16440, successful historical startup
  and requests on 19080, not evidence for today's PID 32104 on 8080.
- seat-management-service/target/seat-e2e-19084.log: PID 30004, successful historical
  startup on 19084, separate from the current PID 18664.

No dedicated current Gateway/Bus/Payment file logs were found in the checked
workspace root, module roots, target directories or conventional logs directories.
Existing terminal console output was not accessible through the available tools.
Consequently current startup/communication stack traces for those PIDs are UNKNOWN.
Historical logs cannot rule out errors in their current processes.

### Exact Errors And Likely Causes

| Evidence | Cause / Confidence |
| --- | --- |
| Booking direct requests: Unable to connect to the remote server | Confirmed absent listener and absent matching process; why Booking stopped is unknown |
| Bus /api/buses: 404 Not Found | Running OpenAPI lacks this collection mapping; source/runtime contract mismatch confirmed |
| Gateway /api/bookings: 404 | Consistent with packaged singular-only Booking predicate; effective overrides unknown |
| Gateway /api/booking: 500, no response body | Backend 8082 is stopped; likely downstream connection failure, but no current Gateway stack trace confirms the exact exception |
| Gateway /api/seats/booking/1: 404; direct Seat 200 | Packaged Gateway has no Seat route; consistent with configuration/runtime mismatch |
| Seat /actuator/health: 500 Unexpected error occurred | Confirmed missing static resource caught by generic Exception handler |

No new port conflict, bean-creation failure or explicit database connection error
was observed. Missing current logs prevent a universal clean-log claim. No literal
Connection refused stack trace was captured during this execution; it must not
be substituted for the exact observed client error or blank Gateway error body.

## Start The Stopped Service

**Commands for the user only; not executed during this verification.** Booking
is the only identified stopped service. In a separate PowerShell terminal:

```powershell
Set-Location 'C:\Users\2564626\Downloads\git pull\bus--booking\booking-service'
if ([string]::IsNullOrWhiteSpace($env:INTERNAL_SERVICE_TOKEN)) {
    throw 'Set INTERNAL_SERVICE_TOKEN to the authorized shared service token before starting Booking.'
}
.\mvnw.cmd spring-boot:run
```

Prerequisites: Java 17, wrapper dependency access, port 8082 still free, and the
authorized shared token matching cooperating Seat/Payment services. Do not send
the token through chat. Default service URLs target Bus 8081 and Seat 8084;
existing environment overrides must agree. The current Seat is a diagnostic
instance; authentication/version compatibility with the older Payment runtime
was not established. The Booking command uses its existing properties and file
database with ddl-auto=update; it does not delete/reset data. It can update schema
when the user runs it, and it is not a guarantee that startup will succeed.

## Final Verdict

Running: Gateway 8080, Bus 8081, Payment 8083, Seat 8084. Not running: Booking 8082.
Working Gateway routes: legacy Bus listing and Payment listing. Failed routes:
current Bus collection, plural Booking, singular Booking backend access and Seat.
All-five simultaneous operation is not verified and is contradicted by Booking's
absence in both snapshots. Full integration testing is blocked by the stopped
Booking service and runtime API/routing mismatches. Read-only health checks do
not certify payment, reservation, concurrency, expiry, recovery or persistence flows.

Only this requested report was created. No Java, properties, scripts or dependencies
were changed; no application was started, restarted or terminated; no port was
changed; no database was deleted or opened directly. Existing services may continue
their own scheduled work independently of these read-only probes.