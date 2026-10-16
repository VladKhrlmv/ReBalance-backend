package com.rebalance.dto.response;

import java.math.BigDecimal;
import java.time.Instant;

public record GroupResponse(
        Long id,
        String name,
        String currency,
        boolean favorite,
        boolean owner,
        Long ownerUserId,
        Long myMemberId,
        BigDecimal myBalance,
        long memberCount,
        String inviteToken,
        boolean inviteEnabled,
        Instant createdAt
) {
}
