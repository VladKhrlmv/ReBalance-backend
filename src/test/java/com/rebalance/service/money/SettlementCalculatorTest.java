package com.rebalance.service.money;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SettlementCalculatorTest {
    @Test
    void tricountStyleExample() {
        // Alice paid 90 for dinner shared by Alice, Bob and Carol; Bob paid 30 for taxis shared by all three.
        // Balances: Alice +50, Bob -10, Carol -40.
        Map<Long, Long> balances = Map.of(1L, 5000L, 2L, -1000L, 3L, -4000L);

        List<SettlementCalculator.Transfer> transfers = SettlementCalculator.settle(balances);

        assertThat(transfers).containsExactly(
                new SettlementCalculator.Transfer(3L, 1L, 4000L),
                new SettlementCalculator.Transfer(2L, 1L, 1000L));
    }

    @Test
    void largestCreditorIsMatchedWithLargestDebtorAndTiesUseLowerId() {
        Map<Long, Long> balances = new LinkedHashMap<>();
        balances.put(5L, 300L);
        balances.put(4L, 300L);
        balances.put(1L, -300L);
        balances.put(2L, -300L);

        assertThat(SettlementCalculator.settle(balances)).containsExactly(
                new SettlementCalculator.Transfer(1L, 4L, 300L),
                new SettlementCalculator.Transfer(2L, 5L, 300L));
    }

    @Test
    void everythingSettledGivesNoTransfers() {
        assertThat(SettlementCalculator.settle(Map.of(1L, 0L, 2L, 0L))).isEmpty();
        assertThat(SettlementCalculator.settle(Map.of())).isEmpty();
    }

    @Test
    void rejectsBalancesThatDoNotSumToZero() {
        assertThatThrownBy(() -> SettlementCalculator.settle(Map.of(1L, 100L, 2L, -99L)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void doesNotModifyInput() {
        Map<Long, Long> balances = new HashMap<>(Map.of(1L, 100L, 2L, -100L));
        SettlementCalculator.settle(balances);
        assertThat(balances).containsEntry(1L, 100L).containsEntry(2L, -100L);
    }

    @RepeatedTest(200)
    void transfersAlwaysZeroEveryBalanceWithAtMostNMinusOneTransfers() {
        Random random = new Random();
        int n = 2 + random.nextInt(25);
        Map<Long, Long> balances = new HashMap<>();
        long sum = 0;
        for (long id = 1; id < n; id++) {
            long balance = random.nextInt(2_000_001) - 1_000_000;
            balances.put(id, balance);
            sum += balance;
        }
        balances.put((long) n, -sum);

        List<SettlementCalculator.Transfer> transfers = SettlementCalculator.settle(balances);

        Map<Long, Long> remaining = new HashMap<>(balances);
        for (SettlementCalculator.Transfer t : transfers) {
            assertThat(t.amountCents()).isPositive();
            assertThat(t.debtorMemberId()).isNotEqualTo(t.creditorMemberId());
            remaining.merge(t.debtorMemberId(), t.amountCents(), Long::sum);
            remaining.merge(t.creditorMemberId(), -t.amountCents(), Long::sum);
        }
        assertThat(remaining.values()).allMatch(v -> v == 0L);
        long nonZero = balances.values().stream().filter(v -> v != 0).count();
        assertThat((long) transfers.size()).isLessThanOrEqualTo(Math.max(0, nonZero - 1));
    }
}
