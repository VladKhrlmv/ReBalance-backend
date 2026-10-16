package com.rebalance.dto.request;

import jakarta.validation.constraints.NotNull;

public record InviteSettingsRequest(
        @NotNull(message = "Enabled value is required")
        Boolean enabled
) {
}
