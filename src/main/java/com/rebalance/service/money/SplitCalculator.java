package com.rebalance.service.money;

import com.rebalance.entity.SplitType;

import java.util.*;

/**
 * Splits an amount between participants in exact cents.
 * <p>
 * EQUAL and SHARES use the largest-remainder method: everyone gets {@code floor(total * w / W)} cents, then the
 * leftover cents go one by one to the participants with the largest remainders (ties: lower member id first).
 * The shares always sum exactly to the total. AMOUNTS takes the caller's amounts and requires them to sum to the total.
 */
public final class SplitCalculator {
    public static final int MAX_WEIGHT = 1000;

    private SplitCalculator() {
    }

    /**
     * @param memberId    participating member
     * @param weight      SHARES weight (1..1000); ignored for EQUAL and AMOUNTS
     * @param amountCents AMOUNTS share in cents (>= 0); ignored for EQUAL and SHARES
     */
    public record Participant(long memberId, Integer weight, Long amountCents) {
    }

    /**
     * @param memberId    participating member
     * @param weight      weight used (1 for EQUAL, null for AMOUNTS)
     * @param amountCents exact share in cents
     */
    public record Share(long memberId, Integer weight, long amountCents) {
    }

    public static class InvalidSplitException extends RuntimeException {
        public InvalidSplitException(String message) {
            super(message);
        }
    }

    public static List<Share> split(SplitType splitType, long totalCents, List<Participant> participants) {
        if (totalCents <= 0) {
            throw new InvalidSplitException("amount must be positive");
        }
        if (participants == null || participants.isEmpty()) {
            throw new InvalidSplitException("at least one participant is required");
        }
        Set<Long> seen = new HashSet<>();
        for (Participant p : participants) {
            if (!seen.add(p.memberId())) {
                throw new InvalidSplitException("each member can appear only once");
            }
        }
        List<Participant> sorted = participants.stream()
                .sorted(Comparator.comparingLong(Participant::memberId))
                .toList();

        return switch (splitType) {
            case EQUAL -> byWeights(totalCents, sorted, sorted.stream().map(p -> 1).toList());
            case SHARES -> {
                List<Integer> weights = new ArrayList<>(sorted.size());
                for (Participant p : sorted) {
                    if (p.weight() == null || p.weight() < 1 || p.weight() > MAX_WEIGHT) {
                        throw new InvalidSplitException("each weight must be between 1 and " + MAX_WEIGHT);
                    }
                    weights.add(p.weight());
                }
                yield byWeights(totalCents, sorted, weights);
            }
            case AMOUNTS -> byAmounts(totalCents, sorted);
        };
    }

    private static List<Share> byWeights(long totalCents, List<Participant> participants, List<Integer> weights) {
        long weightSum = 0;
        for (int w : weights) {
            weightSum += w;
        }

        int n = participants.size();
        long[] cents = new long[n];
        long[] remainders = new long[n];
        long assigned = 0;
        for (int i = 0; i < n; i++) {
            long numerator = Math.multiplyExact(totalCents, (long) weights.get(i));
            cents[i] = numerator / weightSum;
            remainders[i] = numerator % weightSum;
            assigned += cents[i];
        }

        long leftover = totalCents - assigned; // always < n
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        // participants are sorted by member id, so a stable sort keeps the lower id first on ties
        Arrays.sort(order, (a, b) -> Long.compare(remainders[b], remainders[a]));
        for (int k = 0; k < leftover; k++) {
            cents[order[k]]++;
        }

        List<Share> shares = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            shares.add(new Share(participants.get(i).memberId(), weights.get(i), cents[i]));
        }
        return shares;
    }

    private static List<Share> byAmounts(long totalCents, List<Participant> participants) {
        long sum = 0;
        List<Share> shares = new ArrayList<>(participants.size());
        for (Participant p : participants) {
            if (p.amountCents() == null || p.amountCents() < 0) {
                throw new InvalidSplitException("each amount must be zero or positive");
            }
            sum = Math.addExact(sum, p.amountCents());
            shares.add(new Share(p.memberId(), null, p.amountCents()));
        }
        if (sum != totalCents) {
            throw new InvalidSplitException("the amounts add up to " + Money.format(sum)
                    + " but the expense is " + Money.format(totalCents));
        }
        return shares;
    }
}
