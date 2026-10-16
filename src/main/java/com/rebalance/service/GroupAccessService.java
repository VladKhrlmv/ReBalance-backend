package com.rebalance.service;

import com.rebalance.entity.*;
import com.rebalance.exception.RebalanceErrorType;
import com.rebalance.exception.RebalanceException;
import com.rebalance.repository.ExpenseRepository;
import com.rebalance.repository.GroupMemberRepository;
import com.rebalance.repository.GroupRepository;
import com.rebalance.security.SignedInUsernameGetter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * The single place that decides who may touch which group. Non-members get 404 (the API doesn't reveal that a
 * group exists); members who aren't the owner get 403 for owner-only actions.
 */
@Service
@RequiredArgsConstructor
public class GroupAccessService {
    private final GroupMemberRepository memberRepository;
    private final GroupRepository groupRepository;
    private final ExpenseRepository expenseRepository;
    private final SignedInUsernameGetter signedInUsernameGetter;

    /** The current user's ACTIVE membership in a live, shared (non-personal) group. */
    public GroupMember requireMember(Long groupId) {
        User user = signedInUsernameGetter.getUser();
        GroupMember member = memberRepository.findInLiveGroup(groupId, user.getId(), MemberStatus.ACTIVE)
                .orElseThrow(() -> new RebalanceException(RebalanceErrorType.RB_201));
        if (member.getGroup().isPersonal()) {
            throw new RebalanceException(RebalanceErrorType.RB_201);
        }
        return member;
    }

    public GroupMember requireOwner(Long groupId) {
        GroupMember member = requireMember(groupId);
        if (!member.getGroup().isOwnedBy(member.getUser())) {
            throw new RebalanceException(RebalanceErrorType.RB_202);
        }
        return member;
    }

    /** The current user's membership in their own personal group. */
    public GroupMember requirePersonalMember() {
        User user = signedInUsernameGetter.getUser();
        Group personalGroup = groupRepository.findByCreatorIdAndPersonalTrue(user.getId())
                .orElseThrow(() -> new RebalanceException(RebalanceErrorType.RB_201));
        return memberRepository.findByGroupIdAndUserId(personalGroup.getId(), user.getId())
                .orElseThrow(() -> new RebalanceException(RebalanceErrorType.RB_201));
    }

    /** True if the current user is an ACTIVE member of the live group (shared or personal). */
    public boolean canAccessGroup(Long groupId) {
        User user = signedInUsernameGetter.getUser();
        return memberRepository.findInLiveGroup(groupId, user.getId(), MemberStatus.ACTIVE).isPresent();
    }

    /** A live expense of a group the current user belongs to; otherwise 404. */
    public Expense requireAccessibleExpense(Long expenseId) {
        Expense expense = expenseRepository.findByIdAndDeletedAtIsNull(expenseId)
                .orElseThrow(() -> new RebalanceException(RebalanceErrorType.RB_101));
        if (!canAccessGroup(expense.getGroup().getId())) {
            throw new RebalanceException(RebalanceErrorType.RB_101);
        }
        return expense;
    }
}
