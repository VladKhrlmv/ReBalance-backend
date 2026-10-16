package com.rebalance.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record GroupUpdateRequest(
        @NotBlank(message = "Group name is required")
        @Size(max = 100, message = "Group name should be at most 100 characters")
        String name
) {
}
