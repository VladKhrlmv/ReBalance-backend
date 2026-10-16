package com.rebalance.dto.request;

import com.rebalance.dto.common.ValidationConst;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record GroupCreateRequest(
        @NotBlank(message = "Group name is required")
        @Size(max = 100, message = "Group name should be at most 100 characters")
        String name,
        @NotNull(message = "Currency is required")
        @Pattern(regexp = ValidationConst.currencyRegex, message = ValidationConst.currencyMessage)
        String currency
) {
}
