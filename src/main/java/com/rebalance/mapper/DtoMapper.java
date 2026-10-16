package com.rebalance.mapper;

import com.rebalance.dto.response.*;
import com.rebalance.entity.*;
import com.rebalance.service.money.Money;
import com.rebalance.util.Time;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

/**
 * Entity → response mapping. Must be called inside a transaction (associations are lazy).
 */
@Component
public class DtoMapper {
    public GroupResponse group(GroupMember me, long memberCount, long myBalanceCents) {
        Group group = me.getGroup();
        Long ownerUserId = group.getOwner().getId();
        return new GroupResponse(
                group.getId(),
                group.getName(),
                group.getCurrency(),
                me.isFavorite(),
                ownerUserId.equals(me.getUser().getId()),
                ownerUserId,
                me.getId(),
                Money.fromCents(myBalanceCents),
                memberCount,
                group.getInviteToken(),
                group.isInviteEnabled(),
                Time.toInstant(group.getCreatedAt()));
    }

    public MemberResponse member(GroupMember member, Long ownerUserId, long balanceCents) {
        return new MemberResponse(
                member.getId(),
                member.getUser().getId(),
                member.getUser().getNickname(),
                member.getStatus(),
                member.getUser().getId().equals(ownerUserId),
                Money.fromCents(balanceCents),
                Time.toInstant(member.getJoinedAt()));
    }

    public MemberRef memberRef(GroupMember member) {
        return new MemberRef(member.getId(), member.getUser().getId(), member.getUser().getNickname());
    }

    public GroupExpenseResponse groupExpense(Expense expense, boolean hasImage) {
        List<ExpenseShareResponse> shares = expense.getShares().stream()
                .sorted(Comparator.comparing(s -> s.getMember().getId()))
                .map(s -> new ExpenseShareResponse(
                        s.getMember().getId(),
                        s.getMember().getUser().getId(),
                        s.getMember().getUser().getNickname(),
                        s.getWeight(),
                        s.getAmount()))
                .toList();
        return new GroupExpenseResponse(
                expense.getId(),
                expense.getGroup().getId(),
                expense.getType(),
                expense.getSplitType(),
                expense.getDescription(),
                expense.getAmount(),
                expense.getDate(),
                categoryName(expense),
                memberRef(expense.getPayer()),
                shares,
                new UserRef(expense.getCreatedBy().getId(), expense.getCreatedBy().getNickname()),
                Time.toInstant(expense.getCreatedAt()),
                Time.toInstant(expense.getUpdatedAt()),
                expense.getVersion(),
                hasImage);
    }

    public PersonalExpenseResponse personalExpense(Expense expense, boolean hasImage) {
        return new PersonalExpenseResponse(
                expense.getId(),
                expense.getAmount(),
                expense.getDescription(),
                expense.getDate(),
                categoryName(expense),
                expense.getVersion(),
                hasImage);
    }

    public CategoryResponse category(Category category) {
        return new CategoryResponse(category.getId(), category.getName());
    }

    public SumByCategoryResponse sumByCategory(String category, BigDecimal sum) {
        return new SumByCategoryResponse(category, sum);
    }

    public NotificationResponse notification(Notification notification) {
        return new NotificationResponse(
                notification.getId(),
                notification.getType(),
                notification.getGroup() == null ? null : notification.getGroup().getId(),
                notification.getExpenseId(),
                notification.getActor().getId(),
                notification.getActor().getNickname(),
                notification.getMessage(),
                Time.toInstant(notification.getCreatedAt()));
    }

    private static String categoryName(Expense expense) {
        return expense.getCategory() == null ? null : expense.getCategory().getName();
    }
}
