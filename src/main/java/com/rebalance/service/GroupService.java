package com.rebalance.service;

import com.rebalance.dto.request.GroupCreateRequest;
import com.rebalance.dto.response.DebtSettlementResponse;
import com.rebalance.dto.response.GroupResponse;
import com.rebalance.dto.response.MemberResponse;
import com.rebalance.entity.*;
import com.rebalance.exception.RebalanceErrorType;
import com.rebalance.exception.RebalanceException;
import com.rebalance.mapper.DtoMapper;
import com.rebalance.repository.GroupMemberRepository;
import com.rebalance.repository.GroupRepository;
import com.rebalance.security.SignedInUsernameGetter;
import com.rebalance.service.money.Money;
import com.rebalance.service.money.SettlementCalculator;
import com.rebalance.util.Time;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class GroupService {
    private final GroupRepository groupRepository;
    private final GroupMemberRepository memberRepository;
    private final GroupAccessService groupAccessService;
    private final BalanceService balanceService;
    private final NotificationService notificationService;
    private final SignedInUsernameGetter signedInUsernameGetter;
    private final DtoMapper mapper;

    @Transactional(readOnly = true)
    public List<GroupResponse> getMyGroups() {
        User user = signedInUsernameGetter.getUser();
        List<GroupMember> memberships = memberRepository.findActiveSharedMembershipsOfUser(user.getId());
        if (memberships.isEmpty()) {
            return List.of();
        }
        List<Long> groupIds = memberships.stream().map(m -> m.getGroup().getId()).toList();
        Map<Long, Long> counts = memberRepository.countActiveByGroupIds(groupIds).stream()
                .collect(Collectors.toMap(GroupMemberRepository.GroupCount::getGroupId, GroupMemberRepository.GroupCount::getTotal));
        Map<Long, Long> balances = balanceService.balancesOfUserByGroup(user.getId(), groupIds);

        return memberships.stream()
                .map(m -> mapper.group(m, counts.getOrDefault(m.getGroup().getId(), 0L),
                        balances.getOrDefault(m.getGroup().getId(), 0L)))
                .sorted(Comparator.comparing(GroupResponse::favorite).reversed()
                        .thenComparing(GroupResponse::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    @Transactional(readOnly = true)
    public GroupResponse getGroup(Long groupId) {
        return toResponse(groupAccessService.requireMember(groupId));
    }

    @Transactional
    public GroupResponse createGroup(GroupCreateRequest request) {
        User user = signedInUsernameGetter.getUser();

        Group group = new Group();
        group.setName(request.name().trim());
        group.setCurrency(request.currency());
        group.setPersonal(false);
        group.setCreator(user);
        group.setOwner(user);
        group.setInviteToken(InviteTokens.generate());
        group.setInviteEnabled(true);
        group.setCreatedAt(Time.nowUtc());
        group.setUpdatedAt(Time.nowUtc());
        groupRepository.save(group);

        return toResponse(addMember(group, user));
    }

    /** Creates the personal group used for personal expense tracking. */
    @Transactional
    public Group createPersonalGroup(User user, String currency) {
        Group group = new Group();
        group.setName("Personal");
        group.setCurrency(currency);
        group.setPersonal(true);
        group.setCreator(user);
        group.setOwner(user);
        group.setInviteEnabled(false);
        group.setCreatedAt(Time.nowUtc());
        group.setUpdatedAt(Time.nowUtc());
        groupRepository.save(group);
        addMember(group, user);
        return group;
    }

    @Transactional
    public GroupResponse renameGroup(Long groupId, String name) {
        GroupMember me = groupAccessService.requireMember(groupId);
        Group group = me.getGroup();
        String oldName = group.getName();
        String newName = name.trim();
        if (!oldName.equals(newName)) {
            group.setName(newName);
            group.setUpdatedAt(Time.nowUtc());
            notifyGroup(NotificationType.GroupRenamed, me, "%s renamed %s to %s"
                    .formatted(me.getUser().getNickname(), oldName, newName));
        }
        return toResponse(me);
    }

    @Transactional
    public void deleteGroup(Long groupId) {
        GroupMember me = groupAccessService.requireOwner(groupId);
        Group group = me.getGroup();
        notifyGroup(NotificationType.GroupDeleted, me, "%s deleted %s".formatted(me.getUser().getNickname(), group.getName()));
        group.setDeletedAt(Time.nowUtc());
        group.setUpdatedAt(Time.nowUtc());
    }

    @Transactional
    public void setFavorite(Long groupId, boolean favorite) {
        groupAccessService.requireMember(groupId).setFavorite(favorite);
    }

    @Transactional(readOnly = true)
    public List<MemberResponse> getMembers(Long groupId) {
        GroupMember me = groupAccessService.requireMember(groupId);
        List<GroupMember> members = memberRepository.findAllByGroupIdWithUser(groupId);
        Map<Long, Long> balances = balanceService.balancesByMember(groupId, members);
        Long ownerUserId = me.getGroup().getOwner().getId();
        return members.stream()
                .map(m -> mapper.member(m, ownerUserId, balances.getOrDefault(m.getId(), 0L)))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<DebtSettlementResponse> getDebtSettlement(Long groupId) {
        groupAccessService.requireMember(groupId);
        List<GroupMember> members = memberRepository.findAllByGroupIdWithUser(groupId);
        Map<Long, GroupMember> byId = members.stream().collect(Collectors.toMap(GroupMember::getId, Function.identity()));

        return SettlementCalculator.settle(balanceService.balancesByMember(groupId, members)).stream()
                .map(t -> {
                    GroupMember debtor = byId.get(t.debtorMemberId());
                    GroupMember creditor = byId.get(t.creditorMemberId());
                    return new DebtSettlementResponse(
                            debtor.getId(), debtor.getUser().getId(), debtor.getUser().getNickname(),
                            creditor.getId(), creditor.getUser().getId(), creditor.getUser().getNickname(),
                            Money.fromCents(t.amountCents()));
                })
                .sorted(Comparator.comparing(DebtSettlementResponse::debtorNickname, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(DebtSettlementResponse::creditorNickname, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /**
     * Leaving requires a zero balance. If the owner leaves, ownership passes to the longest-standing active member;
     * when the last member leaves, the group is deleted.
     */
    @Transactional
    public void leaveGroup(Long groupId) {
        GroupMember me = groupAccessService.requireMember(groupId);
        memberRepository.lockById(me.getId());
        if (balanceService.balanceOfMember(groupId, me.getId()) != 0) {
            throw new RebalanceException(RebalanceErrorType.RB_203);
        }

        Group group = me.getGroup();
        me.setStatus(MemberStatus.LEFT);
        me.setLeftAt(Time.nowUtc());
        me.setFavorite(false);

        if (group.isOwnedBy(me.getUser())) {
            Optional<GroupMember> successor = memberRepository.findActiveByGroupIdExcept(groupId, me.getId()).stream().findFirst();
            if (successor.isPresent()) {
                group.setOwner(successor.get().getUser());
            } else {
                group.setDeletedAt(Time.nowUtc());
            }
            group.setUpdatedAt(Time.nowUtc());
        }
        notifyGroup(NotificationType.MemberLeft, me, "%s left %s".formatted(me.getUser().getNickname(), group.getName()));
    }

    @Transactional
    public void removeMember(Long groupId, Long memberId) {
        GroupMember me = groupAccessService.requireOwner(groupId);
        GroupMember target = memberRepository.lockById(memberId)
                .filter(m -> m.getGroup().getId().equals(groupId) && m.isActive())
                .orElseThrow(() -> new RebalanceException(RebalanceErrorType.RB_207));
        if (target.getId().equals(me.getId())) {
            throw new RebalanceException(RebalanceErrorType.RB_208);
        }
        if (balanceService.balanceOfMember(groupId, target.getId()) != 0) {
            throw new RebalanceException(RebalanceErrorType.RB_203);
        }

        target.setStatus(MemberStatus.REMOVED);
        target.setLeftAt(Time.nowUtc());
        target.setFavorite(false);

        String message = "%s removed %s from %s".formatted(
                me.getUser().getNickname(), target.getUser().getNickname(), me.getGroup().getName());
        List<Long> recipients = new ArrayList<>(memberRepository.findActiveUserIds(groupId));
        recipients.add(target.getUser().getId());
        notificationService.notify(NotificationType.MemberRemoved, me.getUser(), me.getGroup(), null, message, recipients);
    }

    @Transactional
    public GroupResponse rotateInvite(Long groupId) {
        GroupMember me = groupAccessService.requireOwner(groupId);
        me.getGroup().setInviteToken(InviteTokens.generate());
        me.getGroup().setUpdatedAt(Time.nowUtc());
        return toResponse(me);
    }

    @Transactional
    public GroupResponse setInviteEnabled(Long groupId, boolean enabled) {
        GroupMember me = groupAccessService.requireOwner(groupId);
        me.getGroup().setInviteEnabled(enabled);
        me.getGroup().setUpdatedAt(Time.nowUtc());
        return toResponse(me);
    }

    /** Must run inside a transaction. */
    GroupResponse toResponse(GroupMember me) {
        Long groupId = me.getGroup().getId();
        return mapper.group(me,
                memberRepository.countByGroupIdAndStatus(groupId, MemberStatus.ACTIVE),
                balanceService.balanceOfMember(groupId, me.getId()));
    }

    /** Adds the user as an ACTIVE member, reactivating an earlier membership if there is one. */
    GroupMember addMember(Group group, User user) {
        GroupMember member = group.getId() == null ? null
                : memberRepository.findByGroupIdAndUserId(group.getId(), user.getId()).orElse(null);
        if (member == null) {
            member = new GroupMember();
            member.setGroup(group);
            member.setUser(user);
        }
        member.setStatus(MemberStatus.ACTIVE);
        member.setJoinedAt(Time.nowUtc());
        member.setLeftAt(null);
        return memberRepository.save(member);
    }

    private void notifyGroup(NotificationType type, GroupMember actor, String message) {
        notificationService.notify(type, actor.getUser(), actor.getGroup(), null, message,
                memberRepository.findActiveUserIds(actor.getGroup().getId()));
    }
}
