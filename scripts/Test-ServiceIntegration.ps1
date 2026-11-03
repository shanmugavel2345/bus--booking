param(
    [string]$GatewayUrl = 'http://localhost:8080',
    [string]$BusUrl = 'http://localhost:8081',
    [string]$BookingUrl = 'http://localhost:8082',
    [string]$PaymentUrl = 'http://localhost:8083'
)

$ErrorActionPreference = 'Stop'
$checks = 0

function Assert-Equal($Actual, $Expected, [string]$Message) {
    if ($Actual -ne $Expected) {
        throw "$Message. Expected '$Expected', received '$Actual'"
    }
    $script:checks++
}

function Send-Json([string]$Method, [string]$Url, $Body) {
    Invoke-RestMethod -Method $Method -Uri $Url -ContentType 'application/json' -Body ($Body | ConvertTo-Json) -TimeoutSec 15
}

function Assert-HttpStatus([string]$Method, [string]$Url, $Body, [int]$Expected) {
    try {
        if ($null -eq $Body) {
            Invoke-WebRequest -UseBasicParsing -Method $Method -Uri $Url -TimeoutSec 15 | Out-Null
        } else {
            Send-Json $Method $Url $Body | Out-Null
        }
        throw "Expected HTTP $Expected from $Url but request succeeded"
    } catch [System.Net.WebException] {
        if ($null -eq $_.Exception.Response) { throw }
        Assert-Equal ([int]$_.Exception.Response.StatusCode) $Expected "$Method $Url"
    }
}

$bus = $null
try {
    foreach ($url in @("$BusUrl/api/buses", "$BookingUrl/api/bookings", "$PaymentUrl/api/payments",
            "$GatewayUrl/api/buses", "$GatewayUrl/api/bookings", "$GatewayUrl/api/payments")) {
        $response = Invoke-WebRequest -UseBasicParsing -Uri $url -TimeoutSec 15
        Assert-Equal $response.StatusCode 200 "Independent/gateway GET $url"
    }
    $bus = Send-Json 'Post' "$GatewayUrl/api/buses" @{
        busName = 'Integration Express'; busNumber = "TEST-$([guid]::NewGuid().ToString('N').Substring(0,8))"
        source = 'Chennai'; destination = 'Madurai'; totalSeates = 40; fare = 123.45
    }
    $bookingRequest = @{
        busId = $bus.id; passengerName = 'Integration Passenger'; passengerEmail = 'integration@example.com'
        passengerPhone = '1234567890'; journeyDate = (Get-Date -Format 'yyyy-MM-dd'); numberOfSeats = 3
    }
    $booking = Send-Json 'Post' "$GatewayUrl/api/bookings" $bookingRequest
    Assert-Equal ([decimal]$booking.totalAmount) ([decimal]370.35) 'Fare multiplied by seats'
    Assert-Equal $booking.bookingStatus 'PENDING_PAYMENT' 'Booking starts pending payment with reserved seats'
    $payment = Send-Json 'Post' "$GatewayUrl/api/payments" @{
        bookingId = $booking.id; paymentMethod = 'UPI'; amount = 1.00
    }
    Assert-Equal ([decimal]$payment.amount) ([decimal]370.35) 'Frontend amount ignored'
    Assert-Equal $payment.paymentStatus 'SUCCESS' 'Simulated payment succeeds'
    $savedBooking = Invoke-RestMethod "$BookingUrl/api/bookings/$($booking.id)" -TimeoutSec 15
    Assert-Equal $savedBooking.bookingStatus 'CONFIRMED' 'Confirmation persisted in Booking database'
    $savedPayment = Invoke-RestMethod "$PaymentUrl/api/payments/$($payment.id)" -TimeoutSec 15
    Assert-Equal $savedPayment.transactionId $payment.transactionId 'Payment persisted in Payment database'
    Assert-HttpStatus 'Post' "$GatewayUrl/api/payments" @{
        bookingId = $booking.id; paymentMethod = 'UPI'
    } 409
    $retry = Send-Json 'Post' "$GatewayUrl/api/payments/$($payment.id)/confirm-booking" @{}
    Assert-Equal $retry.paymentStatus 'SUCCESS' 'Confirmation retry is idempotent'
    $cancelled = Send-Json 'Post' "$GatewayUrl/api/bookings" $bookingRequest
    Invoke-RestMethod -Method Put "$GatewayUrl/api/bookings/$($cancelled.id)/cancel" -TimeoutSec 15 | Out-Null
    Assert-HttpStatus 'Post' "$GatewayUrl/api/payments" @{
        bookingId = $cancelled.id; paymentMethod = 'UPI'
    } 409
    Assert-HttpStatus 'Put' "$BookingUrl/internal/bookings/$($booking.id)/confirm" $null 401
    Assert-HttpStatus 'Put' "$GatewayUrl/internal/bookings/$($booking.id)/confirm" $null 404
    $bookingRequest.busId = 9223372036854775807
    Assert-HttpStatus 'Post' "$GatewayUrl/api/bookings" $bookingRequest 404
    Assert-HttpStatus 'Post' "$GatewayUrl/api/payments" @{
        bookingId = 9223372036854775807; paymentMethod = 'UPI'
    } 404
    foreach ($serviceUrl in @($BusUrl, $BookingUrl, $PaymentUrl)) {
        $docs = Invoke-WebRequest -UseBasicParsing "$serviceUrl/v3/api-docs" -TimeoutSec 15
        Assert-Equal $docs.StatusCode 200 "OpenAPI $serviceUrl"
    }
    Write-Output "PASS: $checks live integration assertions"
} finally {
    if ($null -ne $bus) {
        Invoke-RestMethod -Method Delete "$BusUrl/api/buses/$($bus.id)" -TimeoutSec 15 | Out-Null
    }
}