package com.rebalance.dto.response;

import java.math.BigDecimal;

/**
 * "debtor pays creditor amount".
 */
public record DebtSettlementResponse(
        Long debtorMemberId,
        Long debtorUserId,
        String debtorNickname,
        Long creditorMemberId,
        Long creditorUserId,
        String creditorNickname,
        BigDecimal amount
) {
}
