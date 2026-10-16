package com.rebalance.dto.response;

import com.rebalance.entity.ExpenseType;
import com.rebalance.entity.SplitType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record GroupExpenseResponse(
        Long id,
        Long groupId,
        ExpenseType type,
        SplitType splitType,
        String description,
        BigDecimal amount,
        LocalDate date,
        String category,
        MemberRef payer,
        List<ExpenseShareResponse> shares,
        UserRef createdBy,
        Instant createdAt,
        Instant updatedAt,
        Long version,
        boolean hasImage
) {
}
