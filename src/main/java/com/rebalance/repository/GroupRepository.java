package com.rebalance.repository;

import com.rebalance.entity.Group;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface GroupRepository extends JpaRepository<Group, Long> {
    Optional<Group> findByCreatorIdAndPersonalTrue(Long userId);

    Optional<Group> findByInviteTokenAndInviteEnabledTrueAndPersonalFalseAndDeletedAtIsNull(String inviteToken);
}
