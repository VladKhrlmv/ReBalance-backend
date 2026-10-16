package com.rebalance.dto.response;

import java.math.BigDecimal;

public record SumByCategoryResponse(String category, BigDecimal sum) {
}
