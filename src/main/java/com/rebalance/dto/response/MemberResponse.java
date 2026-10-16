package com.rebalance.dto.response;

import com.rebalance.entity.MemberStatus;

import java.math.BigDecimal;
import java.time.Instant;

public record MemberResponse(
        Long memberId,
        Long userId,
        String nickname,
        MemberStatus status,
        boolean owner,
        BigDecimal balance,
        Instant joinedAt
) {
}
