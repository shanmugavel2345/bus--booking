param([int]$BasePort = 19080, [switch]$CompleteVerification)

$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$processes = [System.Collections.Generic.List[System.Diagnostics.Process]]::new()
$appProcesses = @{}
$databaseRoot = Join-Path $env:TEMP ("bus-booking-verification-" + [guid]::NewGuid().ToString('N'))
$savedEnvironment = @{}
$checks = 0

function Assert-Equal($Actual, $Expected, [string]$Message) {
    if ($Actual -ne $Expected) { throw "$Message. Expected '$Expected', received '$Actual'" }
    $script:checks++
}

function Send-Json([string]$Method, [string]$Url, $Body) {
    Invoke-RestMethod -Method $Method -Uri $Url -ContentType 'application/json' -Body ($Body | ConvertTo-Json -Depth 8) -TimeoutSec 15
}

function Assert-Status([string]$Method, [string]$Url, $Body, [int]$Expected) {
    try {
        if ($null -eq $Body) { Invoke-WebRequest -UseBasicParsing -Method $Method -Uri $Url -TimeoutSec 15 | Out-Null }
        else { Send-Json $Method $Url $Body | Out-Null }
        throw "Expected HTTP $Expected from $Url"
    } catch [System.Net.WebException] {
        if ($null -eq $_.Exception.Response) { throw }
        Assert-Equal ([int]$_.Exception.Response.StatusCode) $Expected "$Method $Url"
    }
}

function Start-App([string]$Module, [string]$Main, [int]$Port, [string]$Probe, [string[]]$Extra = @()) {
    $modulePath = Join-Path $root $Module
    $dependencies = (Get-Content (Join-Path $modulePath 'target/seat-e2e-classpath.txt') -Raw).Trim()
    $classpath = (Join-Path $modulePath 'target/classes') + ';' + $dependencies
    $arguments = @('-Xms64m', '-Xmx256m', '-Dspring.devtools.restart.enabled=false', '-cp', ('"' + $classpath + '"'), $Main,
        "--server.port=$Port", '--spring.jpa.hibernate.ddl-auto=create-drop',
        "--spring.datasource.url=jdbc:h2:mem:seat-e2e-$Port;DB_CLOSE_DELAY=-1", '--spring.jpa.show-sql=false',
        '--spring.h2.console.enabled=false', '--booking.seat-recovery-enabled=false', '--payments.recovery-enabled=false') + $Extra
    if ($CompleteVerification) {
        $arguments = @('-Xms64m', '-Xmx256m', '-Dspring.devtools.restart.enabled=false', '-cp', ('"' + $classpath + '"'), $Main,
            "--server.port=$Port", '--spring.jpa.hibernate.ddl-auto=update',
            "--spring.datasource.url=jdbc:h2:file:$($databaseRoot.Replace('\','/'))/service-$Port;DB_CLOSE_ON_EXIT=FALSE",
            '--spring.jpa.show-sql=false', '--spring.h2.console.enabled=false',
            '--seats.reservation-duration=PT30S', '--seats.expiry-delay-ms=1000',
            '--booking.seat-recovery-enabled=true', '--booking.seat-recovery-delay-ms=1000',
            '--payments.recovery-enabled=true', '--payments.recovery-delay-ms=1000') + $Extra
    }
    $process = Start-Process -FilePath (Get-Command java).Source -ArgumentList $arguments -WorkingDirectory $modulePath -PassThru `
        -RedirectStandardOutput (Join-Path $modulePath "target/seat-e2e-$Port.log") `
        -RedirectStandardError (Join-Path $modulePath "target/seat-e2e-$Port.err")
    $null = $process.Handle
    $script:processes.Add($process)
    $script:appProcesses[$Port] = $process
    $deadline = [DateTime]::UtcNow.AddSeconds(60)
    do {
        if ($process.HasExited) {
            $process.WaitForExit()
            throw "Startup failed: $Module (exit $($process.ExitCode)). Read target/seat-e2e-$Port.log and .err"
        }
        try {
            $response = Invoke-WebRequest -UseBasicParsing -Uri "http://localhost:$Port$Probe" -TimeoutSec 2
            if ($response.StatusCode -eq 200) {
                $started = Select-String -Path (Join-Path $modulePath "target/seat-e2e-$Port.log") -Pattern 'Started .* in .* seconds' | Select-Object -Last 1
                if ($null -eq $started) { throw "Readiness API succeeded without a Started log: $Module" }
                Write-Output "Started $Module on $Port (PID $($process.Id)): $($started.Line)"
                return
            }
        } catch [System.Net.WebException] { }
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Startup unconfirmed: $Module on $Port"
}

function Stop-TestApp([int]$Port) {
    $process = $script:appProcesses[$Port]
    if (!$process.HasExited) { Stop-Process -Id $process.Id }
    $process.WaitForExit()
}

function Assert-Eventually([string]$Url, [string]$Field, $Expected, [int]$Seconds = 45) {
    $deadline = [DateTime]::UtcNow.AddSeconds($Seconds)
    do {
        $response = Invoke-RestMethod -Uri $Url -TimeoutSec 3
        if ($response.$Field -eq $Expected) { Assert-Equal $response.$Field $Expected "Worker result $Url"; return }
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Worker deadline exceeded: $Field expected $Expected at $Url"
}

try {
    foreach ($port in $BasePort..($BasePort + 5)) {
        if (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) { throw "Port $port is already in use" }
    }
    foreach ($name in @('INTERNAL_SERVICE_TOKEN', 'BUS_SERVICE_URL', 'BOOKING_SERVICE_URL', 'PAYMENT_SERVICE_URL', 'SEAT_SERVICE_URL')) {
        $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
    }
    $env:INTERNAL_SERVICE_TOKEN = [guid]::NewGuid().ToString('N')
    $env:BUS_SERVICE_URL = "http://localhost:$($BasePort + 1)"
    $env:BOOKING_SERVICE_URL = "http://localhost:$($BasePort + 2)"
    $env:PAYMENT_SERVICE_URL = "http://localhost:$($BasePort + 3)"
    $env:SEAT_SERVICE_URL = "http://localhost:$($BasePort + 4)"
    $gateway = "http://localhost:$BasePort"
    foreach ($module in @('Bus-booking', 'booking-service', 'payment-service/payment_service', 'seat-management-service', 'api-gateway')) {
        & (Join-Path $root 'booking-service/mvnw.cmd') -f (Join-Path $root "$module/pom.xml") compile dependency:build-classpath '-Dmdep.outputFile=target/seat-e2e-classpath.txt' -q
        if ($LASTEXITCODE -ne 0) { throw "Build failed: $module" }
    }
    Start-App 'Bus-booking' 'com.shank.busbooking.BusBookingApplication' ($BasePort + 1) '/api/buses'
    Start-App 'seat-management-service' 'com.shank.seat_management_service.SeatManagementServiceApplication' ($BasePort + 4) '/v3/api-docs'
    Start-App 'booking-service' 'com.shank.booking_service.BookingServiceApplication' ($BasePort + 2) '/api/bookings'
    Start-App 'payment-service/payment_service' 'com.shank.payment_service.PaymentServiceApplication' ($BasePort + 3) '/api/payments' @('--payments.simulation.success=true')
    Start-App 'api-gateway' 'com.shank.api_gateway.ApiGatewayApplication' $BasePort '/api/buses'
    Start-App 'payment-service/payment_service' 'com.shank.payment_service.PaymentServiceApplication' ($BasePort + 5) '/api/payments' @('--payments.simulation.success=false')

    & (Join-Path $PSScriptRoot 'Test-ServiceIntegration.ps1') -GatewayUrl $gateway -BusUrl $env:BUS_SERVICE_URL `
        -BookingUrl $env:BOOKING_SERVICE_URL -PaymentUrl $env:PAYMENT_SERVICE_URL

    $bus = Send-Json Post "$gateway/api/buses" @{
        busName = 'Seat Integration'; busNumber = 'SEAT-E2E'; source = 'Chennai'; destination = 'Madurai'; totalSeates = 4; fare = 123.45
    }
    $date = (Get-Date).AddDays(5).ToString('yyyy-MM-dd')
    $request = @{
        busId = $bus.id; passengerName = 'Seat Passenger'; passengerEmail = 'seat@example.com'; passengerPhone = '1234567890'
        journeyDate = $date; numberOfSeats = 2; seatNumbers = @('S1', 'S2')
    }
    $booking = Send-Json Post "$gateway/api/bookings" $request
    Assert-Equal $booking.bookingStatus 'PENDING_PAYMENT' 'Seat-backed booking status'
    Assert-Equal @($booking.seatNumbers).Count 2 'Selected seats returned'
    $available = Invoke-RestMethod "$gateway/api/seats/bus/$($bus.id)/available?journeyDate=$date"
    Assert-Equal $available.Count 2 'Reserved seats excluded from availability'
    Assert-Status Post "$gateway/api/bookings" $request 409
    $payment = Send-Json Post "$gateway/api/payments" @{ bookingId = $booking.id; paymentMethod = 'UPI' }
    Assert-Equal $payment.paymentStatus 'SUCCESS' 'Successful payment outcome'
    $bookedSeats = Invoke-RestMethod "$gateway/api/seats/booking/$($booking.id)"
    Assert-Equal @($bookedSeats | Where-Object seatStatus -eq 'BOOKED').Count 2 'Successful payment books seats'
    Send-Json Post "$gateway/api/payments/$($payment.id)/confirm-booking" @{} | Out-Null
    Invoke-RestMethod -Method Put "$gateway/api/bookings/$($booking.id)/cancel" | Out-Null
    $available = Invoke-RestMethod "$gateway/api/seats/bus/$($bus.id)/available?journeyDate=$date"
    Assert-Equal $available.Count 4 'Cancellation releases booked seats'

    $failedBooking = Send-Json Post "$gateway/api/bookings" $request
    $failedPayment = Send-Json Post "http://localhost:$($BasePort + 5)/api/payments" @{ bookingId = $failedBooking.id; paymentMethod = 'CARD' }
    Assert-Equal $failedPayment.paymentStatus 'FAILED' 'Failed simulation is never successful'
    $saved = Invoke-RestMethod "$gateway/api/bookings/$($failedBooking.id)"
    Assert-Equal $saved.bookingStatus 'PAYMENT_FAILED' 'Failure remains unconfirmed'
    $available = Invoke-RestMethod "$gateway/api/seats/bus/$($bus.id)/available?journeyDate=$date"
    Assert-Equal $available.Count 4 'Failed payment releases seats'
    Send-Json Post "http://localhost:$($BasePort + 5)/api/payments/$($failedPayment.id)/notify-booking" @{} | Out-Null
    Assert-Status Post "$gateway/api/seats/confirm/$($failedBooking.id)" @{} 401
    Assert-Status Put "$gateway/internal/bookings/$($failedBooking.id)/payment-failed" $null 404
    foreach ($serviceUrl in @($env:BUS_SERVICE_URL, $env:BOOKING_SERVICE_URL, $env:PAYMENT_SERVICE_URL, $env:SEAT_SERVICE_URL)) {
        Assert-Equal (Invoke-WebRequest -UseBasicParsing "$serviceUrl/swagger-ui/index.html" -TimeoutSec 15).StatusCode 200 "Swagger UI $serviceUrl"
        Assert-Equal (Invoke-WebRequest -UseBasicParsing "$serviceUrl/v3/api-docs" -TimeoutSec 15).StatusCode 200 "OpenAPI $serviceUrl"
    }

    if ($CompleteVerification) {
        $headers = @{ 'X-Internal-Token' = $env:INTERNAL_SERVICE_TOKEN }
        $initializeDate = (Get-Date).AddDays(6).ToString('yyyy-MM-dd')
        $initialized = Invoke-RestMethod -Method Post -Uri "$gateway/api/seats/initialize" -Headers $headers -ContentType 'application/json' `
            -Body (@{ busId = $bus.id; journeyDate = $initializeDate } | ConvertTo-Json)
        Assert-Equal $initialized.Count 4 'Explicit Seat initialization through Gateway'
        $inventory = Invoke-RestMethod "$gateway/api/seats/bus/$($bus.id)/available?journeyDate=$initializeDate"
        Assert-Equal $inventory.Count 4 'New journey availability'

        $request.journeyDate = (Get-Date).AddDays(7).ToString('yyyy-MM-dd')
        $request.numberOfSeats = 1
        $request.seatNumbers = @('S1')
        Add-Type -AssemblyName System.Net.Http
        $http = [System.Net.Http.HttpClient]::new()
        try {
            $firstContent = [System.Net.Http.StringContent]::new(($request | ConvertTo-Json), [Text.Encoding]::UTF8, 'application/json')
            $secondContent = [System.Net.Http.StringContent]::new(($request | ConvertTo-Json), [Text.Encoding]::UTF8, 'application/json')
            $first = $http.PostAsync("$gateway/api/bookings", $firstContent)
            $second = $http.PostAsync("$gateway/api/bookings", $secondContent)
            [System.Threading.Tasks.Task]::WaitAll([System.Threading.Tasks.Task[]]@($first, $second))
            $responses = @($first.Result, $second.Result)
            Assert-Equal @($responses | Where-Object { [int]$_.StatusCode -eq 201 }).Count 1 'Concurrent seat requests: one winner'
            Assert-Equal @($responses | Where-Object { [int]$_.StatusCode -eq 409 }).Count 1 'Concurrent seat requests: one conflict'
            $winner = ($responses | Where-Object { [int]$_.StatusCode -eq 201 }).Content.ReadAsStringAsync().Result | ConvertFrom-Json
            $owned = Invoke-RestMethod "$gateway/api/seats/booking/$($winner.id)"
            Assert-Equal $owned.Count 1 'Exactly one persisted seat owner'
            Assert-Eventually "$env:SEAT_SERVICE_URL/api/seats/$($owned[0].id)" 'seatStatus' 'AVAILABLE'
            $expiryLog = Get-Content (Join-Path $root "seat-management-service/target/seat-e2e-$($BasePort + 4).log") -Raw
            Assert-Equal ($expiryLog.Contains("Expired seat reservation bookingId=$($winner.id)")) $true 'Expiry worker logged release without availability-triggered expiry'
            Write-Output 'PASS: real concurrent Booking requests and scheduled 30-second expiry'
        } finally { $http.Dispose() }

        $request.journeyDate = $date
        $request.numberOfSeats = 2
        $request.seatNumbers = @('S1', 'S2')
        $persistedBooking = Send-Json Post "$gateway/api/bookings" $request
        $persistedPayment = Send-Json Post "$gateway/api/payments" @{ bookingId = $persistedBooking.id; paymentMethod = 'UPI' }
        $request.journeyDate = (Get-Date).AddDays(8).ToString('yyyy-MM-dd')
        $recoveryBooking = Send-Json Post "$gateway/api/bookings" $request
        Stop-TestApp ($BasePort + 4)
        Assert-Status Get "$gateway/api/seats/bus/$($bus.id)/available?journeyDate=$date" $null 503
        Assert-Status Post "$gateway/api/payments" @{ bookingId = $recoveryBooking.id; paymentMethod = 'UPI' } 503
        $pending = Invoke-RestMethod "$gateway/api/bookings/$($recoveryBooking.id)"
        Assert-Equal $pending.bookingStatus 'CONFIRMING' 'Seat outage never falsely confirms'
        Start-App 'seat-management-service' 'com.shank.seat_management_service.SeatManagementServiceApplication' ($BasePort + 4) '/v3/api-docs'
        Assert-Eventually "$gateway/api/bookings/$($recoveryBooking.id)" 'bookingStatus' 'CONFIRMED' 20
        $recovered = Invoke-RestMethod "$gateway/api/seats/booking/$($recoveryBooking.id)"
        Assert-Equal @($recovered | Where-Object seatStatus -eq 'BOOKED').Count 2 'Recovery workers confirm persisted hold after Seat restart'
        Write-Output 'PASS: actual Seat outage, persisted pending action and automatic confirmation recovery'

        foreach ($port in $BasePort..($BasePort + 5)) { Stop-TestApp $port }
        Start-App 'Bus-booking' 'com.shank.busbooking.BusBookingApplication' ($BasePort + 1) '/api/buses'
        Start-App 'seat-management-service' 'com.shank.seat_management_service.SeatManagementServiceApplication' ($BasePort + 4) '/v3/api-docs'
        Start-App 'booking-service' 'com.shank.booking_service.BookingServiceApplication' ($BasePort + 2) '/api/bookings'
        Start-App 'payment-service/payment_service' 'com.shank.payment_service.PaymentServiceApplication' ($BasePort + 3) '/api/payments' @('--payments.simulation.success=true')
        Start-App 'api-gateway' 'com.shank.api_gateway.ApiGatewayApplication' $BasePort '/api/buses'
        $savedBus = Invoke-RestMethod "$gateway/api/buses/$($bus.id)"
        Assert-Equal $savedBus.totalSeates 4 'Bus persisted after restart'
        $savedBooking = Invoke-RestMethod "$gateway/api/bookings/$($persistedBooking.id)"
        Assert-Equal $savedBooking.bookingStatus 'CONFIRMED' 'Booking persisted after restart'
        Assert-Equal $savedBooking.reservationId $persistedBooking.reservationId 'Reservation reference persisted'
        $savedPayment = Invoke-RestMethod "$gateway/api/payments/$($persistedPayment.id)"
        Assert-Equal $savedPayment.transactionId $persistedPayment.transactionId 'Payment transaction persisted after restart'
        $savedSeats = Invoke-RestMethod "$gateway/api/seats/booking/$($persistedBooking.id)"
        Assert-Equal @($savedSeats | Where-Object seatStatus -eq 'BOOKED').Count 2 'BOOKED seats persist across application restarts'
        Assert-Equal $savedSeats[0].reservationExpiryTime $null 'BOOKED seats have no active expiry'
        Write-Output "PASS: all four file databases retained records after five-application restart. Files retained at $databaseRoot"
    }
    Write-Output "PASS: $checks seat workflow assertions plus the existing integration script"
} finally {
    foreach ($process in $processes) {
        if (!$process.HasExited) { Stop-Process -Id $process.Id -ErrorAction SilentlyContinue }
        $process.WaitForExit()
        $process.Dispose()
    }
    foreach ($name in $savedEnvironment.Keys) { [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], 'Process') }
    Write-Output 'Temporary JVMs stopped; existing file databases were not opened.'
    if ($CompleteVerification) { Write-Output "Dedicated verification databases retained at $databaseRoot" }
}