package com.rebalance.service;

import com.rebalance.dto.response.NotificationResponse;
import com.rebalance.entity.*;
import com.rebalance.mapper.DtoMapper;
import com.rebalance.repository.NotificationRecipientRepository;
import com.rebalance.repository.NotificationRepository;
import com.rebalance.repository.UserRepository;
import com.rebalance.security.SignedInUsernameGetter;
import com.rebalance.util.Time;
import com.rebalance.websocket.NotificationCreatedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class NotificationService {
    private static final int MAX_MESSAGE_LENGTH = 500;
    private static final int MAX_UNSEEN = 50;

    private final NotificationRepository notificationRepository;
    private final NotificationRecipientRepository recipientRepository;
    private final UserRepository userRepository;
    private final SignedInUsernameGetter signedInUsernameGetter;
    private final ApplicationEventPublisher eventPublisher;
    private final DtoMapper mapper;

    /**
     * Stores a notification for the given users (the actor is never notified about their own action) and
     * schedules its WebSocket delivery for after the surrounding transaction commits.
     */
    @Transactional
    public void notify(NotificationType type, User actor, Group group, Long expenseId, String message,
                       Collection<Long> recipientUserIds) {
        Set<Long> recipients = new LinkedHashSet<>(recipientUserIds);
        recipients.remove(actor.getId());
        if (recipients.isEmpty()) {
            return;
        }

        Notification notification = new Notification();
        notification.setType(type);
        notification.setActor(actor);
        notification.setGroup(group);
        notification.setExpenseId(expenseId);
        notification.setMessage(message.length() > MAX_MESSAGE_LENGTH ? message.substring(0, MAX_MESSAGE_LENGTH - 1) + "…" : message);
        notification.setCreatedAt(Time.nowUtc());
        notificationRepository.save(notification);

        List<User> users = userRepository.findAllById(recipients);
        for (User user : users) {
            NotificationRecipient recipient = new NotificationRecipient();
            recipient.setNotification(notification);
            recipient.setUser(user);
            recipientRepository.save(recipient);
        }

        eventPublisher.publishEvent(new NotificationCreatedEvent(
                users.stream().map(User::getEmail).toList(),
                mapper.notification(notification)));
    }

    @Transactional(readOnly = true)
    public List<NotificationResponse> getUnseen() {
        User user = signedInUsernameGetter.getUser();
        return recipientRepository.findUnseen(user.getId(), PageRequest.of(0, MAX_UNSEEN)).stream()
                .map(recipient -> mapper.notification(recipient.getNotification()))
                .toList();
    }

    @Transactional
    public void markSeen(Collection<Long> notificationIds) {
        if (notificationIds.isEmpty()) {
            return;
        }
        User user = signedInUsernameGetter.getUser();
        recipientRepository.markSeen(user.getId(), notificationIds, Time.nowUtc());
    }
}
