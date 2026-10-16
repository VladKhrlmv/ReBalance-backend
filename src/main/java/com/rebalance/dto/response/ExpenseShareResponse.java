package com.rebalance.dto.response;

import java.math.BigDecimal;

public record ExpenseShareResponse(Long memberId, Long userId, String nickname, Integer weight, BigDecimal amount) {
}
