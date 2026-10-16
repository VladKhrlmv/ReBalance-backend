package com.rebalance.repository;

import com.rebalance.entity.NotificationRecipient;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

@Repository
public interface NotificationRecipientRepository extends JpaRepository<NotificationRecipient, Long> {
    @Query("SELECT r FROM NotificationRecipient r JOIN FETCH r.notification n JOIN FETCH n.actor " +
            "WHERE r.user.id = :userId AND r.seenAt IS NULL ORDER BY n.createdAt DESC, n.id DESC")
    List<NotificationRecipient> findUnseen(Long userId, Pageable pageable);

    @Modifying
    @Query("UPDATE NotificationRecipient r SET r.seenAt = :now " +
            "WHERE r.user.id = :userId AND r.notification.id IN :notificationIds AND r.seenAt IS NULL")
    int markSeen(Long userId, Collection<Long> notificationIds, LocalDateTime now);
}
