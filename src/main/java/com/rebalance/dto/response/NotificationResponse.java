package com.rebalance.dto.response;

import com.rebalance.entity.NotificationType;

import java.time.Instant;

public record NotificationResponse(
        Long id,
        NotificationType type,
        Long groupId,
        Long expenseId,
        Long actorUserId,
        String actorNickname,
        String message,
        Instant createdAt
) {
}
