package com.rebalance.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PersonalExpenseResponse(
        Long id,
        BigDecimal amount,
        String description,
        LocalDate date,
        String category,
        Long version,
        boolean hasImage
) {
}
