package com.rebalance.service;

import com.rebalance.dto.response.GroupResponse;
import com.rebalance.dto.response.InvitePreviewResponse;
import com.rebalance.entity.Group;
import com.rebalance.entity.GroupMember;
import com.rebalance.entity.MemberStatus;
import com.rebalance.entity.NotificationType;
import com.rebalance.entity.User;
import com.rebalance.exception.RebalanceErrorType;
import com.rebalance.exception.RebalanceException;
import com.rebalance.repository.GroupMemberRepository;
import com.rebalance.repository.GroupRepository;
import com.rebalance.security.SignedInUsernameGetter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Joining groups through a shareable link. The token in the link works like a key: anyone who has it can join
 * while the owner keeps the link enabled.
 */
@Service
@RequiredArgsConstructor
public class InviteService {
    private final GroupRepository groupRepository;
    private final GroupMemberRepository memberRepository;
    private final GroupService groupService;
    private final NotificationService notificationService;
    private final SignedInUsernameGetter signedInUsernameGetter;

    @Transactional(readOnly = true)
    public InvitePreviewResponse preview(String token) {
        Group group = findGroup(token);
        Optional<User> user = signedInUsernameGetter.findUser();
        boolean alreadyMember = user
                .flatMap(u -> memberRepository.findByGroupIdAndUserId(group.getId(), u.getId()))
                .map(GroupMember::isActive)
                .orElse(false);
        return new InvitePreviewResponse(group.getId(), group.getName(), group.getCurrency(),
                memberRepository.countByGroupIdAndStatus(group.getId(), MemberStatus.ACTIVE), alreadyMember);
    }

    @Transactional
    public GroupResponse join(String token) {
        User user = signedInUsernameGetter.getUser();
        Group group = findGroup(token);

        Optional<GroupMember> existing = memberRepository.findByGroupIdAndUserId(group.getId(), user.getId());
        if (existing.isPresent() && existing.get().isActive()) {
            return groupService.toResponse(existing.get());
        }

        GroupMember member = groupService.addMember(group, user);
        notificationService.notify(NotificationType.MemberJoined, user, group, null,
                "%s joined %s".formatted(user.getNickname(), group.getName()),
                memberRepository.findActiveUserIds(group.getId()));
        return groupService.toResponse(member);
    }

    private Group findGroup(String token) {
        if (token == null || token.length() > 64) {
            throw new RebalanceException(RebalanceErrorType.RB_206);
        }
        return groupRepository.findByInviteTokenAndInviteEnabledTrueAndPersonalFalseAndDeletedAtIsNull(token)
                .orElseThrow(() -> new RebalanceException(RebalanceErrorType.RB_206));
    }
}
