package com.rebalance.dto.request;

import com.rebalance.dto.common.ValidationConst;
import com.rebalance.entity.ExpenseType;
import com.rebalance.entity.SplitType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record ExpenseRequest(
        ExpenseType type,
        @NotNull(message = "Split type is required")
        SplitType splitType,
        @NotBlank(message = "Description is required")
        @Size(max = 255, message = "Description should be at most 255 characters")
        String description,
        @NotNull(message = "Amount is required")
        @DecimalMin(value = "0.01", message = "Amount should be positive")
        @DecimalMax(value = ValidationConst.maxAmount, message = "Amount is too large")
        @Digits(integer = 9, fraction = 2, message = "Amount can have at most 2 decimals")
        BigDecimal amount,
        LocalDate date,
        @Size(max = 64, message = "Category should be at most 64 characters")
        String category,
        @NotNull(message = "Payer is required")
        Long payerMemberId,
        @NotEmpty(message = "There should be at least one participant")
        @Size(max = 200, message = "Too many participants")
        List<@Valid @NotNull ExpenseShareRequest> shares,
        Long version
) {
}
