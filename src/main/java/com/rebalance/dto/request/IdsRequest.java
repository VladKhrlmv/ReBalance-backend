package com.rebalance.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record IdsRequest(
        @NotNull(message = "Ids are required")
        @Size(max = 500, message = "Too many ids")
        List<@NotNull Long> ids
) {
}
