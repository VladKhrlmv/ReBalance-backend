package com.rebalance.dto.request;

import com.rebalance.dto.common.ValidationConst;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

public record SettlementRequest(
        @NotNull(message = "Debtor is required")
        Long debtorMemberId,
        @NotNull(message = "Creditor is required")
        Long creditorMemberId,
        @NotNull(message = "Amount is required")
        @DecimalMin(value = "0.01", message = "Amount should be positive")
        @DecimalMax(value = ValidationConst.maxAmount, message = "Amount is too large")
        @Digits(integer = 9, fraction = 2, message = "Amount can have at most 2 decimals")
        BigDecimal amount,
        LocalDate date
) {
}
