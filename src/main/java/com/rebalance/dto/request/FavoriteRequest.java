package com.rebalance.dto.request;

import jakarta.validation.constraints.NotNull;

public record FavoriteRequest(
        @NotNull(message = "Favorite value is required")
        Boolean favorite
) {
}
