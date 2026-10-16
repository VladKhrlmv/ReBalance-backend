package com.rebalance.websocket;

import com.rebalance.dto.response.NotificationResponse;

import java.util.List;

/**
 * Published when a notification is stored; delivered over WebSocket after the transaction commits.
 */
public record NotificationCreatedEvent(List<String> recipientEmails, NotificationResponse notification) {
}
