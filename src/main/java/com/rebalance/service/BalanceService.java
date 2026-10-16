package com.rebalance.service;

import com.rebalance.entity.GroupMember;
import com.rebalance.repository.ExpenseRepository;
import com.rebalance.service.money.Money;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Balances are derived from the expenses every time (nothing stores a running total):
 * balance = Σ amounts the member paid − Σ the member's shares, over non-deleted expenses.
 * Positive means the others owe this member.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BalanceService {
    private final ExpenseRepository expenseRepository;

    /**
     * @return memberId → balance in cents, for the given members (members without expenses get 0)
     */
    public Map<Long, Long> balancesByMember(Long groupId, Collection<GroupMember> members) {
        Map<Long, Long> balances = new LinkedHashMap<>();
        members.forEach(m -> balances.put(m.getId(), 0L));
        expenseRepository.sumPaidByMember(groupId)
                .forEach(row -> balances.merge(row.getMemberId(), Money.toCents(row.getTotal()), Long::sum));
        expenseRepository.sumSharesByMember(groupId)
                .forEach(row -> balances.merge(row.getMemberId(), -Money.toCents(row.getTotal()), Long::sum));

        long sum = balances.values().stream().mapToLong(Long::longValue).sum();
        if (sum != 0) {
            // can only happen if data was modified outside the application
            log.error("Balances of group {} don't sum to zero (off by {} cents)", groupId, sum);
        }
        return balances;
    }

    public long balanceOfMember(Long groupId, Long memberId) {
        long paid = expenseRepository.sumPaidByMember(groupId).stream()
                .filter(row -> row.getMemberId().equals(memberId))
                .mapToLong(row -> Money.toCents(row.getTotal()))
                .sum();
        long owed = expenseRepository.sumSharesByMember(groupId).stream()
                .filter(row -> row.getMemberId().equals(memberId))
                .mapToLong(row -> Money.toCents(row.getTotal()))
                .sum();
        return paid - owed;
    }

    /**
     * @return groupId → the user's balance in cents in that group
     */
    public Map<Long, Long> balancesOfUserByGroup(Long userId, Collection<Long> groupIds) {
        Map<Long, Long> balances = new HashMap<>();
        if (groupIds.isEmpty()) {
            return balances;
        }
        groupIds.forEach(id -> balances.put(id, 0L));
        expenseRepository.sumPaidByUserPerGroup(userId, groupIds)
                .forEach(row -> balances.merge(row.getGroupId(), Money.toCents(row.getTotal()), Long::sum));
        expenseRepository.sumSharesByUserPerGroup(userId, groupIds)
                .forEach(row -> balances.merge(row.getGroupId(), -Money.toCents(row.getTotal()), Long::sum));
        return balances;
    }
}
