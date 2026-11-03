# API Gateway

Java 17, Spring Boot 3.5.7, Spring Cloud 2025.0.0,
and Spring Cloud Gateway Server WebFlux. Default port: 8080.

## IntelliJ

Open this directory's pom.xml as a Maven project, or add it as a Maven
project in the existing workspace. Select JDK 17 and reload Maven.
Run ApiGatewayApplication when ready.

## Verify the setup

From the repository root, reuse the existing Maven wrapper:

```powershell
.\booking-service\mvnw.cmd -f api-gateway/pom.xml test
```

## Routing

| Incoming path | Destination |
| --- | --- |
| /api/buses/** | http://localhost:8081 |
| /api/booking/** | http://localhost:8082 |
| /api/bookings/** | http://localhost:8082 |
| /api/payments/** | http://localhost:8083 |
| /api/seats/** | http://localhost:8084 |

Preserve the incoming paths; the services already use these prefixes.
Start the four downstream services separately when testing your routes.
The gateway does not start them and needs no database.

Override destinations using BUS_SERVICE_URL, BOOKING_SERVICE_URL, and
PAYMENT_SERVICE_URL and SEAT_SERVICE_URL environment variables. Connections time out after 3 seconds;
downstream responses time out after 10 seconds. No automatic retries are enabled.

Tests use an isolated HTTP backend and gateway on random ports to verify routing,
HTTP methods, request bodies, query strings, response headers, backend errors,
and unmatched paths without running real services.

CORS allows `FRONTEND_ORIGIN` (default `http://localhost:3000`) with GET, POST,
PUT, DELETE and OPTIONS, and Content-Type / Authorization headers. Credentials
are not enabled. Upstream connection failures produce a safe JSON HTTP 503;
gateway response timeouts may produce HTTP 504. Request logs contain method,
path and status, not passenger details or payment request bodies.
Internal booking confirmation is deliberately not routed.
Seat POST requests remain protected by the Seat service's internal token check.
React can query seats here, but must create/cancel bookings through Booking;
do not expose the shared service token to a browser.

Authentication and service discovery are not configured. This is an
unauthenticated local-development gateway, not an Internet-ready deployment.
See the [integration guide](../README.md) for all-service startup and live tests.