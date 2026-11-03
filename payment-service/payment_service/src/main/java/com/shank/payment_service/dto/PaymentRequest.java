package com.shank.payment_service.dto;

import java.math.BigDecimal;
import com.shank.payment_service.entity.PaymentMethod;
import jakarta.validation.constraints.*;

public record PaymentRequest(
        @NotNull @Positive Long bookingId,
        @DecimalMin("0.01") @Digits(integer = 17, fraction = 2) BigDecimal amount,
        @NotNull PaymentMethod paymentMethod) {
}