package com.rebalance.dto.request;

import com.rebalance.dto.common.ValidationConst;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PersonalExpenseRequest(
        @NotNull(message = "Amount is required")
        @DecimalMin(value = "0.01", message = "Amount should be positive")
        @DecimalMax(value = ValidationConst.maxAmount, message = "Amount is too large")
        @Digits(integer = 9, fraction = 2, message = "Amount can have at most 2 decimals")
        BigDecimal amount,
        @NotBlank(message = "Description is required")
        @Size(max = 255, message = "Description should be at most 255 characters")
        String description,
        @Size(max = 64, message = "Category should be at most 64 characters")
        String category,
        LocalDate date,
        Long version
) {
}
