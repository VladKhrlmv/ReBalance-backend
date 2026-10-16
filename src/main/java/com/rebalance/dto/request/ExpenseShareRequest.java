package com.rebalance.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record ExpenseShareRequest(
        @NotNull(message = "Member id is required")
        Long memberId,
        Integer weight,
        @DecimalMin(value = "0", message = "Share amount can't be negative")
        @Digits(integer = 9, fraction = 2, message = "Share amount can have at most 2 decimals")
        BigDecimal amount
) {
}
