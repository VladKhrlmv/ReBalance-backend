package com.rebalance.repository;

import com.rebalance.entity.GroupMember;
import com.rebalance.entity.MemberStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface GroupMemberRepository extends JpaRepository<GroupMember, Long> {
    Optional<GroupMember> findByGroupIdAndUserId(Long groupId, Long userId);

    @Query("SELECT m FROM GroupMember m JOIN FETCH m.group g " +
            "WHERE g.id = :groupId AND m.user.id = :userId AND m.status = :status AND g.deletedAt IS NULL")
    Optional<GroupMember> findInLiveGroup(Long groupId, Long userId, MemberStatus status);

    @Query("SELECT m FROM GroupMember m JOIN FETCH m.user WHERE m.group.id = :groupId ORDER BY m.joinedAt ASC, m.id ASC")
    List<GroupMember> findAllByGroupIdWithUser(Long groupId);

    /**
     * Locks the given members of a group for the rest of the transaction, so nobody can leave while an
     * expense referencing them is being saved (and vice versa).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM GroupMember m WHERE m.group.id = :groupId AND m.id IN :memberIds")
    List<GroupMember> lockAllByGroupIdAndIdIn(Long groupId, Collection<Long> memberIds);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM GroupMember m WHERE m.id = :memberId")
    Optional<GroupMember> lockById(Long memberId);

    @Query("SELECT m FROM GroupMember m JOIN FETCH m.group g " +
            "WHERE m.user.id = :userId AND m.status = com.rebalance.entity.MemberStatus.ACTIVE " +
            "AND g.personal = false AND g.deletedAt IS NULL")
    List<GroupMember> findActiveSharedMembershipsOfUser(Long userId);

    @Query("SELECT m FROM GroupMember m WHERE m.group.id = :groupId AND m.status = com.rebalance.entity.MemberStatus.ACTIVE " +
            "AND m.id <> :excludedMemberId ORDER BY m.joinedAt ASC, m.id ASC")
    List<GroupMember> findActiveByGroupIdExcept(Long groupId, Long excludedMemberId);

    long countByGroupIdAndStatus(Long groupId, MemberStatus status);

    @Query("SELECT m.group.id AS groupId, COUNT(m) AS total FROM GroupMember m " +
            "WHERE m.group.id IN :groupIds AND m.status = com.rebalance.entity.MemberStatus.ACTIVE GROUP BY m.group.id")
    List<GroupCount> countActiveByGroupIds(Collection<Long> groupIds);

    @Query("SELECT m.user.id FROM GroupMember m WHERE m.group.id = :groupId AND m.status = com.rebalance.entity.MemberStatus.ACTIVE")
    List<Long> findActiveUserIds(Long groupId);

    interface GroupCount {
        Long getGroupId();

        Long getTotal();
    }
}
