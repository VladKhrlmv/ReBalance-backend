package com.rebalance.websocket;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Service
@RequiredArgsConstructor
public class WSService {
    public static final String NOTIFICATIONS_DESTINATION = "/queue/notifications";

    // absent when the application runs without a web server (admin commands)
    private final ObjectProvider<SimpMessagingTemplate> messagingTemplate;

    /**
     * Sent only after commit, so clients that refetch on a notification see the new data.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onNotificationCreated(NotificationCreatedEvent event) {
        SimpMessagingTemplate template = messagingTemplate.getIfAvailable();
        if (template == null) {
            return;
        }
        for (String email : event.recipientEmails()) {
            try {
                // user destinations resolve by principal name, which is the user's email
                template.convertAndSendToUser(email, NOTIFICATIONS_DESTINATION, event.notification());
            } catch (RuntimeException e) {
                log.warn("Could not deliver notification {} over WebSocket", event.notification().id(), e);
            }
        }
    }
}
