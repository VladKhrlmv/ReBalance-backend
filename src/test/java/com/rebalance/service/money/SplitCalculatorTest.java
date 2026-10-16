package com.rebalance.service.money;

import com.rebalance.entity.SplitType;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SplitCalculatorTest {
    private static SplitCalculator.Participant equal(long memberId) {
        return new SplitCalculator.Participant(memberId, null, null);
    }

    private static SplitCalculator.Participant weighted(long memberId, int weight) {
        return new SplitCalculator.Participant(memberId, weight, null);
    }

    private static SplitCalculator.Participant amount(long memberId, long cents) {
        return new SplitCalculator.Participant(memberId, null, cents);
    }

    private static List<Long> cents(List<SplitCalculator.Share> shares) {
        return shares.stream().map(SplitCalculator.Share::amountCents).toList();
    }

    @Test
    void equalSplitGivesLeftoverCentToLowestMemberId() {
        List<SplitCalculator.Share> shares = SplitCalculator.split(SplitType.EQUAL, 10000,
                List.of(equal(30), equal(10), equal(20)));

        assertThat(shares).extracting(SplitCalculator.Share::memberId).containsExactly(10L, 20L, 30L);
        assertThat(cents(shares)).containsExactly(3334L, 3333L, 3333L);
        assertThat(shares).allMatch(s -> s.weight() == 1);
    }

    @Test
    void equalSplitWithTwoLeftoverCents() {
        // 1.00 / 3 = 0.3333..; 0.02 / 3 → (1, 1, 0) after floors of 0
        assertThat(cents(SplitCalculator.split(SplitType.EQUAL, 2, List.of(equal(1), equal(2), equal(3)))))
                .containsExactly(1L, 1L, 0L);
    }

    @Test
    void sharesSplitUsesLargestRemainders() {
        // 10.00 with weights 1:2 → 333.33.. and 666.66.. cents → floors 333 and 666, leftover 1 goes to the
        // larger remainder (member 2: remainder 2/3 vs member 1: 1/3)
        List<SplitCalculator.Share> shares = SplitCalculator.split(SplitType.SHARES, 1000,
                List.of(weighted(1, 1), weighted(2, 2)));
        assertThat(cents(shares)).containsExactly(333L, 667L);
        assertThat(shares).extracting(SplitCalculator.Share::weight).containsExactly(1, 2);
    }

    @Test
    void sharesSplitRejectsInvalidWeights() {
        assertThatThrownBy(() -> SplitCalculator.split(SplitType.SHARES, 1000, List.of(weighted(1, 0))))
                .isInstanceOf(SplitCalculator.InvalidSplitException.class);
        assertThatThrownBy(() -> SplitCalculator.split(SplitType.SHARES, 1000, List.of(equal(1))))
                .isInstanceOf(SplitCalculator.InvalidSplitException.class);
        assertThatThrownBy(() -> SplitCalculator.split(SplitType.SHARES, 1000, List.of(weighted(1, 1001))))
                .isInstanceOf(SplitCalculator.InvalidSplitException.class);
    }

    @Test
    void amountsSplitMustAddUpToTotal() {
        List<SplitCalculator.Share> shares = SplitCalculator.split(SplitType.AMOUNTS, 1000,
                List.of(amount(1, 250), amount(2, 750), amount(3, 0)));
        assertThat(cents(shares)).containsExactly(250L, 750L, 0L);
        assertThat(shares).allMatch(s -> s.weight() == null);

        assertThatThrownBy(() -> SplitCalculator.split(SplitType.AMOUNTS, 1000, List.of(amount(1, 250), amount(2, 700))))
                .isInstanceOf(SplitCalculator.InvalidSplitException.class)
                .hasMessageContaining("9.50")
                .hasMessageContaining("10.00");
        assertThatThrownBy(() -> SplitCalculator.split(SplitType.AMOUNTS, 1000, List.of(amount(1, -1), amount(2, 1001))))
                .isInstanceOf(SplitCalculator.InvalidSplitException.class);
        assertThatThrownBy(() -> SplitCalculator.split(SplitType.AMOUNTS, 1000, List.of(equal(1))))
                .isInstanceOf(SplitCalculator.InvalidSplitException.class);
    }

    @Test
    void rejectsDuplicatesEmptyAndNonPositiveTotals() {
        assertThatThrownBy(() -> SplitCalculator.split(SplitType.EQUAL, 1000, List.of(equal(1), equal(1))))
                .isInstanceOf(SplitCalculator.InvalidSplitException.class);
        assertThatThrownBy(() -> SplitCalculator.split(SplitType.EQUAL, 1000, List.of()))
                .isInstanceOf(SplitCalculator.InvalidSplitException.class);
        assertThatThrownBy(() -> SplitCalculator.split(SplitType.EQUAL, 0, List.of(equal(1))))
                .isInstanceOf(SplitCalculator.InvalidSplitException.class);
    }

    @RepeatedTest(200)
    void sharesAlwaysSumToTotalAndDifferByAtMostOneCentFromTheExactShare() {
        Random random = new Random();
        long total = 1 + random.nextInt(10_000_000);
        int n = 1 + random.nextInt(20);
        boolean weightedSplit = random.nextBoolean();

        List<SplitCalculator.Participant> participants = new ArrayList<>();
        long weightSum = 0;
        for (int i = 0; i < n; i++) {
            int weight = weightedSplit ? 1 + random.nextInt(SplitCalculator.MAX_WEIGHT) : 1;
            weightSum += weight;
            participants.add(weightedSplit ? weighted(i + 1, weight) : equal(i + 1));
        }

        List<SplitCalculator.Share> shares = SplitCalculator.split(weightedSplit ? SplitType.SHARES : SplitType.EQUAL,
                total, participants);

        assertThat(shares.stream().mapToLong(SplitCalculator.Share::amountCents).sum()).isEqualTo(total);
        for (SplitCalculator.Share share : shares) {
            double exact = (double) total * share.weight() / weightSum;
            assertThat(Math.abs(share.amountCents() - exact)).isLessThan(1.0);
        }
    }
}
