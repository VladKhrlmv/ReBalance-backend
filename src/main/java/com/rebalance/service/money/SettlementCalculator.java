package com.rebalance.service.money;

import java.util.*;

/**
 * Suggests who should pay whom so that every balance reaches zero.
 * <p>
 * Greedy, on integer cents: repeatedly match the largest creditor with the largest debtor (ties: lower member id).
 * Every step zeroes at least one balance, so there are at most n-1 transfers. The input map is never modified.
 */
public final class SettlementCalculator {
    private SettlementCalculator() {
    }

    public record Transfer(long debtorMemberId, long creditorMemberId, long amountCents) {
    }

    /**
     * @param balances memberId → balance in cents (positive = is owed money); must sum to zero
     */
    public static List<Transfer> settle(Map<Long, Long> balances) {
        long sum = 0;
        for (long b : balances.values()) {
            sum += b;
        }
        if (sum != 0) {
            throw new IllegalArgumentException("Balances must sum to zero, got " + sum);
        }

        Comparator<long[]> byAmountThenId = (a, b) -> a[1] != b[1] ? Long.compare(b[1], a[1]) : Long.compare(a[0], b[0]);
        // entries are {memberId, absolute amount}
        PriorityQueue<long[]> creditors = new PriorityQueue<>(byAmountThenId);
        PriorityQueue<long[]> debtors = new PriorityQueue<>(byAmountThenId);
        balances.forEach((memberId, balance) -> {
            if (balance > 0) {
                creditors.add(new long[]{memberId, balance});
            } else if (balance < 0) {
                debtors.add(new long[]{memberId, -balance});
            }
        });

        List<Transfer> transfers = new ArrayList<>();
        while (!creditors.isEmpty() && !debtors.isEmpty()) {
            long[] creditor = creditors.poll();
            long[] debtor = debtors.poll();
            long amount = Math.min(creditor[1], debtor[1]);
            transfers.add(new Transfer(debtor[0], creditor[0], amount));
            creditor[1] -= amount;
            debtor[1] -= amount;
            if (creditor[1] > 0) {
                creditors.add(creditor);
            }
            if (debtor[1] > 0) {
                debtors.add(debtor);
            }
        }
        return transfers;
    }
}
