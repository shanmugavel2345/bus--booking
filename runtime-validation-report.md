# Runtime Validation Report

Date: 2026-11-03. Environment: Windows PowerShell, Java 17.0.2, Maven wrappers.

## Latest Complete Verification Retests

**Overall: FAIL / BLOCKED.** Two additional complete isolated runs were explicitly
approved. Each rebuilt all modules successfully, then stopped at a startup gate;
no end-to-end, concurrency, expiry, recovery or persistence scenarios were reached.

1. Before the harness fix, Bus PID 29328, Seat PID 30004 and Booking PID 12184
  became HTTP-ready on 19081, 19084 and 19082. Payment PID 24380 stopped during
  Hikari initialization, before readiness. Stderr was empty and the exit code
  was blank; its exact termination cause remains unknown.
2. A focused `java -version` comparison reproduced the diagnostic defect:
  `Start-Process` without a retained process handle returned a blank exit code;
  retaining `$process.Handle` returned `0`. The harness now retains that handle
  and calls `WaitForExit()` before reporting an exited child. A test invoking
  the actual modified `Start-App` with an intentionally missing main class
  passed by requiring its error to contain `(exit 1)`.
3. After that fix, Bus PID 34516 stopped before readiness with native exit
  `-1073741510`, hexadecimal `0xC000013A`. Windows `RtlNtStatusToDosError`
  mapped it to Win32 error 572; `Win32Exception` returned:
  `{Application Exit by CTRL+C} The application terminated as a result of a CTRL+C`.
  This proves the latest Bus termination status, not the identity of the sender
  and not the causes of previous Seat or Payment exits. No Spring exception
  was captured, and changing business logic is not supported by this evidence.

Both commands exited 1 and stopped only their temporary JVMs. New verification
databases were retained at these locations, not deleted:

```text
C:/Users/2564626/AppData/Local/Temp/bus-booking-verification-3c5b364e7f78434582f2e545461d9ec0
C:/Users/2564626/AppData/Local/Temp/bus-booking-verification-f7a537845d504bf18df156475b809f14
```

Latest Bus evidence: `Bus-booking/target/seat-e2e-19081.log` and `.err`.
Harness log filenames are reused; earlier contents at those paths have been
overwritten by later runs. Historical statements below describe earlier results.
Final listener check still found the diagnostic Seat PID 18664 on 8084 and the
pre-existing Gateway, Bus and Payment listeners. Booking 8082 remains offline.
Required next gate: identify and prevent the source of console interruption,
then rerun complete verification. Existing databases must remain preserved.

## Latest Seat Troubleshooting

**Seat readiness: PASS on 8084. Overall workflow: still unverified.**
No application source or existing databases changed during this diagnostic.

- An independent debugger launch attached, then ended without HTTP readiness.
  Its termination output was unavailable; its exact cause remains unknown.
- The user explicitly approved one terminal diagnostic retry with a dedicated
  file database and captured output. The initial terminal command exited 1 before
  Spring started: PowerShell split the unquoted JVM system-property argument,
  producing `ClassNotFoundException: /devtools/restart/enabled=false`.
  Quoting `'-Dspring.devtools.restart.enabled=false'` corrected that command.
  The harness already quotes this option in its argument array; this diagnosis
  must not be attributed to the earlier harness or debugger failures.
- The corrected command started Seat PID 18664 on 8084 in 7.938 seconds.
  `GET http://localhost:8084/v3/api-docs` succeeded and exposed all nine Seat
  routes. Logging is captured in
  `seat-management-service/target/seat-startup-diagnostic.log`.
  The dedicated database is
  `C:/Users/2564626/AppData/Local/Temp/bus-booking-seat-diagnostic-20261103/seatdb`;
  `ddl-auto=update` was used. This is not a migration check of existing data.
- Latest listener checks found Gateway PID 32104, Bus PID 36456 and Payment
  PID 16204. Booking 8082 is now unavailable; preservation of its previous
  debug session must not be assumed. Seat PID 18664 is the new diagnostic JVM.
- Bus 8081 OpenAPI exposes `/api/buses/{id}`, `/api/buses/createBus` and
  `/api/buses/getAllBus`, but not the current collection endpoint. Payment 8083
  exposes only collection, ID and booking lookup routes, not the current retry
  routes. These running contracts differ from current source. Refreshing those
  runtimes requires preserving their databases; rewriting current controllers
  to fit old processes is not warranted by this evidence.

The earlier abrupt Seat exit and Booking 500 remain undiagnosed. Current
end-to-end, concurrency, expiry, recovery and restart-persistence checks have not
yet run. The 137 automated tests and earlier live assertions below are previous
results, not new verification from this diagnostic.

## Earlier Complete Runtime Verification

**Overall: FAIL / BLOCKED.** The new complete-runtime command exited **1** during
Seat startup. This attempt does not verify the complete five-service workflow.
The passing results recorded in the previous sections below are historical and
must not be interpreted as a successful current runtime run.

Scope: verification only. Application source, service dependencies and existing
databases were not changed. The existing test harness was extended with an opt-in
`-CompleteVerification` mode. The user approved isolated ports and new dedicated
file databases instead of replacing the active applications on 8080-8083.
No AWS, Docker, Kafka, Eureka or other infrastructure changes were performed.

### Maven Import And Build Results

All five Maven application/test source compilations and existing test suites passed
in this attempt, exit 0. Command from the repository root:

```powershell
foreach ($module in @('Bus-booking','booking-service','payment-service/payment_service','seat-management-service','api-gateway')) {
   & .\booking-service\mvnw.cmd -f "$module/pom.xml" test -q
   if ($LASTEXITCODE -ne 0) { throw "Build/tests failed: $module" }
}
```

| Module | Compilation | Tests Passed | Failed / Errors / Skipped | Exit Code |
| --- | --- | --- | --- | --- |
| Bus | PASS | 3 | 0 / 0 / 0 | 0 |
| Booking | PASS | 51 | 0 / 0 / 0 | 0 |
| Payment | PASS | 43 | 0 / 0 / 0 | 0 |
| Seat | PASS | 19 | 0 / 0 / 0 | 0 |
| Gateway | PASS | 21 | 0 / 0 / 0 | 0 |
| Total | PASS | 137 | 0 / 0 / 0 | 0 |

Evidence: module `target/surefire-reports/*.txt` and `TEST-*.xml`. These are unit,
HTTP-contract, gateway and Spring-context/H2 integration tests, not proof of the
current five-process runtime. The expected Seat uniqueness-violation log belongs
to a passing negative test. No production compilation errors required source changes.
Executable JAR packaging was not separately verified; Maven `test` compiles both
main and test code, and the runtime harness compiles/builds dependency classpaths.

**Editor Maven-import verification is incomplete:** Bus still reports
`Project configuration is not up-to-date with pom.xml, requires an update` and
package-path/unresolved-import diagnostics in VS Code. Its Java package names do
not follow the extra physical `bus_service` directory, although Maven compiles them.
Booking, Payment, Seat and Gateway POMs reported no diagnostics; Seat no longer has
the previous non-project-file diagnostics. Do not equate a successful Maven CLI
build with a correctly refreshed editor import. No applicable editor-refresh tool
was available for this existing workspace, and no package rewrite was performed.

### Fresh Service Startup Results

Executed from the repository root:

```powershell
.\scripts\Test-SeatIntegration.ps1 -CompleteVerification
```

The harness uses Java main classes with Maven-generated classpaths, a 64 MB initial /
256 MB maximum heap, bounded startup API probes and a required `Started` log.
It preserves the existing Booking debug session and existing listeners:
Gateway PID 32104, Bus PID 36456, Booking PID 32336, Payment PID 16204.

| Service | Test Port | Result | Evidence |
| --- | --- | --- | --- |
| Bus | 19081 | PASS | PID 34712; `Started BusBookingApplication in 4.811 seconds`; GET /api/buses returned 200 |
| Seat | 19084 | FAIL | PID 34628; JVM exited before `Started`; stderr empty; last log during Hibernate initialization |
| Booking | 19082 | NOT STARTED | Harness stopped after Seat startup failure |
| Payment | 19083 | NOT STARTED | Harness stopped after Seat startup failure |
| Gateway | 19080 | NOT STARTED | Harness stopped after Seat startup failure |
| Extra failed-payment simulation | 19085 | NOT STARTED | Harness stopped after Seat startup failure |

Seat connected to its dedicated H2 file and initialized Tomcat, but neither alone
proves readiness. No Java exception, application crash event or system resource-
exhaustion event was found. The cause of the abrupt exit is **not established**;
the child exit code was unavailable from the returned process object. The overall
PowerShell command exited 1. No automatic relaunch or alternate launch bypass was
attempted after this failure. All temporary JVMs were stopped by the harness.

Logs: `Bus-booking/target/seat-e2e-19081.log`,
`seat-management-service/target/seat-e2e-19084.log` and corresponding `.err` files.

Dedicated verification databases were retained, not deleted:

```text
C:\Users\2564626\AppData\Local\Temp\bus-booking-verification-3b0a3065d1ed4a5189458fa99c7c7d89
service-19081.mv.db: 20480 bytes
service-19084.mv.db: 16384 bytes
```

The opt-in mode uses new file H2 URLs and `ddl-auto=update`, never create-drop.
For practical worker verification it overrides hold duration to PT30S and worker
delays to 1000 ms. The normal PT10M / 30000 ms application defaults are unchanged.
Those worker/restart scenarios were not reached in this failed attempt.

### Current Standard-Port API Results

These were **read-only probes of pre-existing processes**, not fresh startup or
certification that the latest compiled code is loaded. No records were created,
updated, cancelled or deleted in their existing databases.

| Request | Observed Result |
| --- | --- |
| GET http://localhost:8080/api/buses | 404 |
| GET http://localhost:8081/api/buses | 404 |
| GET http://localhost:8082/api/bookings | 500 |
| GET http://localhost:8083/api/payments | 200 |
| GET http://localhost:8080/api/bookings | 404 |
| GET http://localhost:8080/api/payments | 200 |
| GET http://localhost:8080/api/seats/bus/1/available?journeyDate=2026-11-08 | 404 |
| GET http://localhost:8084/v3/api-docs | Connection failed; no listener |
| GET /swagger-ui/index.html on 8081, 8082, 8083 | All 200 |
| GET /v3/api-docs on 8081, 8082, 8083 | All 200 |

Payment is reachable through Gateway. The existing Gateway did not successfully
route the tested Bus, Booking or Seat requests. Backend Bus also returned 404 and
Booking returned 500, so this is not solely a Gateway routing issue. Their causes
were not established in this verification. Swagger availability is not evidence
that business APIs are healthy. No Actuator health feature was added; readiness
uses actual API responses and startup logs.

### Requirements And Integration Verdict

| Requested Scenario | Current Evidence / Verdict |
| --- | --- |
| Compile all five and run unit/integration tests | PASS: 137 tests; exit 0 |
| All Maven projects imported correctly | INCOMPLETE: Bus editor configuration/package diagnostics remain |
| Fresh startup and service readiness | FAIL: Bus ready, Seat exited; remaining startup skipped |
| Gateway routes all four backends | NOT VERIFIED for fresh apps; current standard-port probes failed three route groups |
| Bus creation through Gateway | NOT RUN in fresh runtime after startup failure; covered by passing existing tests |
| Seat initialization / availability | NOT RUN in fresh runtime; covered by Seat tests |
| Booking single / multiple / duplicate reservations | NOT RUN in fresh runtime; covered by Booking and Seat tests |
| Payment success confirms seats | NOT RUN in fresh runtime; automated tests and historical live run only |
| Payment failure releases seats | NOT RUN in fresh runtime; automated tests and historical live run only |
| Scheduled expiry / recovery workers | NOT RUN in fresh runtime; deterministic automated expiry/retry tests pass |
| Persistence across application restart | NOT VERIFIED: fresh apps did not reach record creation/restart |
| All Swagger endpoints | Current Bus/Booking/Payment UI and JSON pass; Seat unreachable; fresh five-app check incomplete |
| Customer -> Gateway -> Booking -> Seat -> Payment -> confirmation | NOT VERIFIED in this attempt |
| Two concurrent requests cannot reserve the same seat | Passing two-thread transactional Seat test; new concurrent HTTP scenario NOT RUN |

The extended harness contains the remaining runnable HTTP scenarios: initialization,
multiple selection, duplicate rejection, simultaneous Booking POSTs, expiry via a
read-only single-seat endpoint, real Seat outage/automatic recovery, Swagger and
five-app restart with persisted Bus/Booking/Payment/Seat record comparisons.
Only its syntax was verified after the failed run; no unexecuted assertion is
counted as passing. No partial fresh-runtime HTTP flow is claimed as overall success.

### Exact Normal Startup Commands

Use five PowerShell terminals initially opened at the repository root, Java 17,
and the **same nonblank secret value** in Seat, Booking and Payment. Do not commit
or expose that token to a browser. Before using standard ports, explicitly stop
the old instances yourself and back up existing H2 files; this verification did
not stop them or validate existing-file migrations. Never run two processes
against one H2 file. Each service must start from its own directory.

Terminal 1 - Bus, 8081:

```powershell
Set-Location .\Bus-booking
.\mvnw.cmd spring-boot:run
```

Terminal 2 - Seat, 8084:

```powershell
$env:INTERNAL_SERVICE_TOKEN='<same-local-secret>'
Set-Location .\seat-management-service
.\mvnw.cmd spring-boot:run
```

Terminal 3 - Booking, 8082:

```powershell
$env:INTERNAL_SERVICE_TOKEN='<same-local-secret>'
Set-Location .\booking-service
.\mvnw.cmd spring-boot:run
```

Terminal 4 - Payment, 8083:

```powershell
$env:INTERNAL_SERVICE_TOKEN='<same-local-secret>'
$env:PAYMENT_SIMULATION_SUCCESS='true'
Set-Location .\payment-service\payment_service
.\mvnw.cmd spring-boot:run
```

Terminal 5 - Gateway, 8080:

```powershell
.\booking-service\mvnw.cmd -f .\api-gateway\pom.xml spring-boot:run
```

The documented default direct URLs are localhost:8081 Bus, :8082 Booking, :8083
Payment and :8084 Seat. Remove unintended URL environment overrides or set
BUS_SERVICE_URL, BOOKING_SERVICE_URL, PAYMENT_SERVICE_URL and SEAT_SERVICE_URL
consistently. To test failure normally, restart only Payment with
`PAYMENT_SIMULATION_SUCCESS=false` and use a new booking. The isolated harness
instead starts an extra failed-simulation Payment instance with its own database.

### Remaining Issues And Next Gate

1. Diagnose the abrupt Seat JVM exit with reliable process exit/crash evidence.
2. Refresh Bus Maven configuration and resolve editor import/package diagnostics
  without changing public APIs merely to hide diagnostics.
3. Diagnose current Bus 404, Booking 500 and Gateway missing-route behavior before
  assuming the running standard-port instances contain the latest implementation.
4. After an identified launch cause is fixed or an explicit retry is requested,
  rerun `.\scripts\Test-SeatIntegration.ps1 -CompleteVerification`. Persistence,
  automatic recovery, live concurrency and the full customer flow remain required gates.
5. Existing-file migrations, default ten-minute wall-clock expiry, real provider
  settlement/refunds and multi-replica behavior remain outside the evidence here.

```text
environment:
  infra-tier: repository H2; new dedicated file databases, existing files untouched
  browser-tier: SKIPPED - API-only; Swagger HTTP resources probed
  aws: NOT USED
build-and-integration: PASS - exit_code: 0, passed: 137, failed: 0, skipped: 0
startup: FAIL - harness exit_code: 1; Bus ready, Seat exited before readiness
fresh-e2e: NOT RUN - exit_code: 1 at startup; no downstream flow assertions executed
existing-api-probes: PARTIAL - Payment/Swagger reachable; Bus/Booking/Seat routes fail
overall: FAIL - complete runtime verification blocked; no successful five-app claim
```

## Previous Seat Implementation Results

The following results supersede the historical four-service baseline below.
Final five-module Maven regression exited **0**: **137 passed, 0 failed, 0 skipped**.

| Module | Suite | Passed |
| --- | --- | --- |
| Bus | BusApiTests | 2 |
| Bus | BusBookingApplicationTests | 1 |
| Booking | BookingApiTests | 31 |
| Booking | BusClientTests | 12 |
| Booking | SeatServiceClientTests | 8 |
| Payment | PaymentApiTests | 29 |
| Payment | BookingServiceClientTests | 14 |
| Seat | SeatApplicationTests | 15 |
| Seat | BusServiceClientTests | 4 |
| Gateway | ApiGatewayApplicationTests | 20 |
| Gateway | GatewayErrorHandlerTests | 1 |
| Total | | 137 |

Command per module: `booking-service/mvnw.cmd -f <module>/pom.xml test -q`.
Evidence: each module's `target/surefire-reports/*.txt` and `TEST-*.xml`.
No test used an existing file database. The logged H2 unique-key exception is
expected by the explicit database-constraint regression, not a test failure.

Coverage added: date-specific initialization and duplicate protection, actual
Bus `totalSeates` contract, availability, invalid/duplicate selection, all-or-none
rollback, two concurrent transactional seat requests with exactly one winner,
DB uniqueness outside the service layer, idempotent reserve/confirm/release/cancel,
stale callbacks after ownership reuse, precise expiry boundary, repeated expiry
worker execution, BOOKED immunity to expiry, all nine Seat routes, token enforcement,
malformed/missing queries and Swagger. Booking tests cover selected/automatic
allocation, persisted reservation metadata, compensation, failed compensation
retry, pending confirmation during Seat errors and payment-failure release.
Payment tests cover expired-hold rejection before persistence, authenticated
success/failure notification, persisted outcomes after notification errors and
manual retry. Gateway tests include Seat paths, bodies and query forwarding.

### Executed Live HTTP Run

`.\scripts\Test-SeatIntegration.ps1` exited **0** with:

```text
Started Bus-booking on 19081
Started seat-management-service on 19084
Started booking-service on 19082
Started payment-service/payment_service on 19083
Started api-gateway on 19080
Started payment-service/payment_service on 19085
PASS: 22 live integration assertions
PASS: 12 seat workflow assertions plus the existing integration script
Temporary JVMs stopped; existing file databases were not opened.
```

**34 live assertions passed.** Five distinct applications used isolated in-memory
H2; an extra Payment instance on 19085 simulated FAILED. Real HTTP verified
selected holds excluded from availability, a conflicting booking returned 409,
successful payment made seats BOOKED, repeat notification was safe, cancellation
restored all four seats, failed payment persisted FAILED/PAYMENT_FAILED and
released seats, protected Seat mutations returned 401 and internal Booking
paths remained unrouted. The original script verified authoritative pricing,
read-back, duplicate-payment prevention, legacy paths and OpenAPI resources.

A later optional Seat-JVM-outage extension could not run: Bus exited during
startup without a Java exception or Windows application crash event. Its cause
was not established and no automatic relaunch was attempted. That extension was
removed, restoring the previously passing script. **No live Seat-outage success
is claimed.** Current outage/compensation cases passed in automated tests; the
older live four-service outage results below are historical, not new Seat evidence.

### Remaining Boundaries

Maven compilation and runtime startup passed, but VS Code reports the newly added
Seat module as a non-project file until Java/Maven import is refreshed. Do not
change valid package declarations to match that stale editor diagnostic.
No editor-refresh tool applicable to this existing workspace was available.

No AWS/RDS, Docker, Kafka or Eureka actions were taken. Existing JVMs, debugger
and durable H2 files were preserved. Existing-file schema/H2 compatibility and
additive-column migration were not tested. Runtime databases still use update;
create-drop was used only for disposable memory tests. No real payments, refunds,
customer authentication, production monitoring, load testing, cross-replica
Payment idempotency, multi-host H2 certification or distributed atomicity is claimed.
Late SUCCESS after expiry and PENDING after a process crash require reconciliation.
The scheduled expiry worker was invoked deterministically in tests, not verified
by a ten-minute wall-clock live wait. Slow/hung downstream timeout timing remains
untested. Postman JSON parsed and local documentation links resolved; its runner
was not executed. Mermaid validation was attempted but blocked by the extension's
sign-in requirement; no renderer validation or preview success is claimed.
Maven and live HTTP are the executed verification gates.

## Historical Four-Service Baseline

The remainder records the earlier integration before the Seat service was added.
Statements about absent seats/recovery below apply only to that historical version.

## Strategy And Scope

API-only application. Preserve existing JUnit/MockMvc suites with necessary contract
changes; add Bus CRUD, REST client HTTP contract, status/authentication, recovery,
and gateway error tests. Tests use isolated H2 databases and JDK HTTP stubs.
Real E2E uses all four application processes with separate in-memory H2 databases.
Docker is explicitly excluded by the request; no containers were attempted.
No rendered application UI exists, so browser/Node/Playwright validation is not
required. Swagger resource availability is covered at the API level.

## Executed Maven Results

Command per module: `booking-service/mvnw.cmd -f <module>/pom.xml test -q`.

| Module | Suite | Passed | Failed | Skipped |
| --- | --- | --- | --- | --- |
| Bus | BusApiTests | 2 | 0 | 0 |
| Bus | BusBookingApplicationTests | 1 | 0 | 0 |
| Booking | BookingApiTests | 25 | 0 | 0 |
| Booking | BusClientTests | 12 | 0 | 0 |
| Payment | PaymentApiTests | 27 | 0 | 0 |
| Payment | BookingServiceClientTests | 13 | 0 | 0 |
| Gateway | ApiGatewayApplicationTests | 17 | 0 | 0 |
| Gateway | GatewayErrorHandlerTests | 1 | 0 | 0 |
| Total | | 98 | 0 | 0 |

Final combined test command exited 0. Evidence: each module's
`target/surefire-reports/*.txt` and `TEST-*.xml`.

Booking tests verify decimal pricing, bus not-found/unavailable without persistence,
validation, legacy paths, plural path/date alias, authentication and idempotent
confirmation, cancellation conflict, read-back and Swagger. Payment tests verify
authoritative amounts, missing/unavailable/cancelled/confirmed bookings, successful
simulation, FAILED without confirmation, duplicate rejection, saved SUCCESS after
notification failure and retry. Real HTTP client tests verify DTOs, paths, methods,
internal token use, invalid payloads, HTTP status mapping and refused connections.
Gateway tests cover routing/method/body/query/error preservation, CORS allow/reject,
internal-path isolation and safe unavailable errors. Bus tests use an isolated
database, exercise CRUD and legacy aliases, and verify Swagger resources.

## Live Four-Service Validation

Applications launched with Maven-generated runtime classpaths and Java main classes
on ports 18081 Bus, 18082 Booking, 18083 Payment, 18080 Gateway. Each reported
Started under Boot 3.5.7. Startup times: Bus 4.674s, Booking 5.557s,
Payment 5.046s, Gateway 4.260s. Existing instances on 8080-8083 were not restarted.

Executed:

```powershell
.\scripts\Test-ServiceIntegration.ps1 -GatewayUrl http://localhost:18080 -BusUrl http://localhost:18081 -BookingUrl http://localhost:18082 -PaymentUrl http://localhost:18083
```

Exit 0: `PASS: 22 live integration assertions`.

- All three direct collection APIs and all three gateway routes returned 200.
- Created bus fare 123.45 and booking for 3 seats through Gateway; total 370.35.
- Sent frontend amount 1.00; payment persisted authoritative 370.35 as SUCCESS.
- Read back CONFIRMED directly from Booking and persisted transaction from Payment.
- Repeat payment returned 409; confirmation retry succeeded without another payment.
- Cancelled booking payment returned 409.
- Internal confirmation without token returned 401; through Gateway returned 404.
- Missing bus and missing booking payment returned 404.
- All three service OpenAPI endpoints returned 200.

Additional fault injection, all commands exited 0, seven assertions:

1. Stopped isolated Bus: gateway bus GET 503; booking POST 503; booking count unchanged.
2. Stopped isolated Booking: gateway booking GET 503; payment POST 503; payment count unchanged.
3. Stopped isolated Payment: gateway payment GET 503.

All four temporary terminals were stopped after verification. Temporary H2 data
was discarded. Existing file databases and active debugger were not changed.

## Resolved Validation Blockers

Initial `clean test` could not remove a JAR locked by an existing process. Testing
without `clean` succeeded. Bus test compilation then exposed stale generated
class output inconsistent with source package declarations. Removed only generated
classes/test-classes/maven-status directories and rebuilt all four modules; all
98 tests passed. No source package change or user-data deletion was needed.

## Gaps And Verdict

No real provider, multi-replica idempotency, crash recovery, seat inventory,
distributed transaction, cancellation compensation, load test, TLS or customer
authorization has been verified. Network timeout configuration is present;
refused connections were tested, but slow/hung upstream timeout timing was not.
Existing persistent database format/schema migration was not performed. H2 tests
do not certify PostgreSQL or MySQL compatibility. Failed simulation and notification
recovery were exercised in API/service tests, not in the live four-process script.

```text
environment:
  docker: NOT USED - explicitly excluded by user
  node: NOT REQUIRED - API-only
  playwright: NOT REQUIRED - no rendered frontend
  infra-tier: H2 - repository database technology, isolated memory instances
  browser-tier: SKIPPED - API-only
startup: PASS - all four applications reported Started on isolated ports
integration: PASS - exit_code: 0, passed: 98, failed: 0, skipped: 0
e2e: PASS - exit_code: 0, passed: 29, failed: 0, gateway and internal HTTP boundaries
overall: PASS - requested local simulated flow; not production certification
```