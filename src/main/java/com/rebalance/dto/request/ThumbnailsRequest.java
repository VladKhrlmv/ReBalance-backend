package com.rebalance.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record ThumbnailsRequest(
        @NotNull(message = "Expense ids are required")
        @Size(max = 100, message = "At most 100 expense ids")
        List<@NotNull Long> expenseIds
) {
}
