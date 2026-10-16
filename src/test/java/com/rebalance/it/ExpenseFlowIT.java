package com.rebalance.it;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExpenseFlowIT extends IntegrationTestBase {
    @Test
    void equalSplitStoresExactCentsAndBalancesSumToZero() throws Exception {
        TestUser alice = register("Alice");
        TestUser bob = register("Bob");
        TestUser carol = register("Carol");
        TestGroup trip = createGroup(alice, "Trip");
        long a = trip.ownerMemberId();
        long b = join(trip, bob);
        long c = join(trip, carol);

        JsonNode expense = addEqualExpense(trip, alice, "100.00", a, List.of(a, b, c));
        assertThat(expense.get("shares")).hasSize(3);
        assertThat(expense.get("shares").get(0).get("amount").decimalValue()).isEqualByComparingTo("33.34");
        assertThat(expense.get("shares").get(1).get("amount").decimalValue()).isEqualByComparingTo("33.33");
        assertThat(expense.get("shares").get(2).get("amount").decimalValue()).isEqualByComparingTo("33.33");
        assertThat(expense.get("payer").get("nickname").asText()).isEqualTo("Alice");
        assertThat(expense.get("version").asLong()).isZero();
        assertThat(expense.get("date").asText()).matches("\\d{4}-\\d{2}-\\d{2}");

        Map<Long, BigDecimal> balances = balances(trip, bob);
        assertThat(balances.get(a)).isEqualByComparingTo("66.66");
        assertThat(balances.get(b)).isEqualByComparingTo("-33.33");
        assertThat(balances.get(c)).isEqualByComparingTo("-33.33");
        assertThat(balances.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("0");

        JsonNode myGroups = get("/v1/user/groups", bob.token()).expect(200).body();
        assertThat(myGroups).hasSize(1);
        assertThat(myGroups.get(0).get("myBalance").decimalValue()).isEqualByComparingTo("-33.33");
        assertThat(myGroups.get(0).get("memberCount").asLong()).isEqualTo(3);
    }

    @Test
    void sharesAndAmountsSplits() throws Exception {
        TestUser alice = register("Alice");
        TestUser bob = register("Bob");
        TestGroup group = createGroup(alice, "Flat");
        long a = group.ownerMemberId();
        long b = join(group, bob);

        JsonNode shares = post("/v1/group/" + group.id() + "/expenses", bob.token(), Map.of(
                "splitType", "SHARES", "description", "Rent", "amount", 1000, "payerMemberId", b,
                "shares", List.of(Map.of("memberId", a, "weight", 1), Map.of("memberId", b, "weight", 3))))
                .expect(201).body();
        assertThat(shares.get("shares").get(0).get("amount").decimalValue()).isEqualByComparingTo("250.00");
        assertThat(shares.get("shares").get(0).get("weight").asInt()).isEqualTo(1);

        var mismatch = post("/v1/group/" + group.id() + "/expenses", bob.token(), Map.of(
                "splitType", "AMOUNTS", "description", "Groceries", "amount", "50.00", "payerMemberId", a,
                "shares", List.of(Map.of("memberId", a, "amount", "20.00"), Map.of("memberId", b, "amount", "20.00"))))
                .expect(400);
        assertThat(mismatch.errorCode()).isEqualTo("RB_103");

        post("/v1/group/" + group.id() + "/expenses", bob.token(), Map.of(
                "splitType", "AMOUNTS", "description", "Groceries", "amount", "50.00", "payerMemberId", a,
                "shares", List.of(Map.of("memberId", a, "amount", "20.00"), Map.of("memberId", b, "amount", "30.00"))))
                .expect(201);

        // Bob: paid 1000, owes 750 + 30 → +220; Alice: paid 50, owes 250 + 20 → -220
        Map<Long, BigDecimal> balances = balances(group, alice);
        assertThat(balances.get(b)).isEqualByComparingTo("220.00");
        assertThat(balances.get(a)).isEqualByComparingTo("-220.00");

        var tooPrecise = post("/v1/group/" + group.id() + "/expenses", bob.token(), Map.of(
                "splitType", "EQUAL", "description", "x", "amount", "1.005", "payerMemberId", a,
                "shares", List.of(Map.of("memberId", a)))).expect(400);
        assertThat(tooPrecise.errorCode()).isEqualTo("RB_998");
    }

    @Test
    void payerAndParticipantsMustBeActiveMembers() throws Exception {
        TestUser alice = register("Alice");
        TestUser mallory = register("Mallory");
        TestGroup group = createGroup(alice, "Mine");
        TestGroup other = createGroup(mallory, "Other");

        var foreignMember = post("/v1/group/" + group.id() + "/expenses", alice.token(), Map.of(
                "splitType", "EQUAL", "description", "x", "amount", 10, "payerMemberId", group.ownerMemberId(),
                "shares", List.of(Map.of("memberId", other.ownerMemberId())))).expect(400);
        assertThat(foreignMember.errorCode()).isEqualTo("RB_104");

        var duplicate = post("/v1/group/" + group.id() + "/expenses", alice.token(), Map.of(
                "splitType", "EQUAL", "description", "x", "amount", 10, "payerMemberId", group.ownerMemberId(),
                "shares", List.of(Map.of("memberId", group.ownerMemberId()), Map.of("memberId", group.ownerMemberId()))))
                .expect(400);
        assertThat(duplicate.errorCode()).isEqualTo("RB_105");
    }

    @Test
    void staleEditsAndDeletesAreRejected() throws Exception {
        TestUser alice = register("Alice");
        TestUser bob = register("Bob");
        TestGroup group = createGroup(alice, "Trip");
        long a = group.ownerMemberId();
        long b = join(group, bob);
        JsonNode expense = addEqualExpense(group, alice, "40.00", a, List.of(a, b));
        String url = "/v1/group/" + group.id() + "/expenses/" + expense.get("id").asLong();

        Map<String, Object> edit = Map.of(
                "splitType", "EQUAL", "description", "Dinner and drinks", "amount", "60.00", "payerMemberId", a,
                "shares", List.of(Map.of("memberId", a), Map.of("memberId", b)), "version", 0);
        JsonNode edited = put(url, bob.token(), edit).expect(200).body();
        assertThat(edited.get("version").asLong()).isEqualTo(1);
        assertThat(edited.get("amount").decimalValue()).isEqualByComparingTo("60.00");

        // Alice still has version 0 open
        assertThat(put(url, alice.token(), edit).expect(409).errorCode()).isEqualTo("RB_102");
        assertThat(delete(url + "?version=0", alice.token()).expect(409).errorCode()).isEqualTo("RB_102");

        delete(url + "?version=1", alice.token()).expect(204);
        get(url, alice.token()).expect(404);
        assertThat(balances(group, alice).get(a)).isEqualByComparingTo("0");
        assertThat(get("/v1/group/" + group.id() + "/expenses", alice.token()).expect(200).body().get("totalElements").asLong())
                .isZero();
    }

    @Test
    void settlingUpBringsEveryoneToZero() throws Exception {
        TestUser alice = register("Alice");
        TestUser bob = register("Bob");
        TestUser carol = register("Carol");
        TestGroup group = createGroup(alice, "Weekend");
        long a = group.ownerMemberId();
        long b = join(group, bob);
        long c = join(group, carol);

        addEqualExpense(group, alice, "90.00", a, List.of(a, b, c));
        addEqualExpense(group, bob, "30.00", b, List.of(a, b, c));

        JsonNode suggestions = get("/v1/group/" + group.id() + "/debt-settlement", carol.token()).expect(200).body();
        assertThat(suggestions).hasSize(2);
        for (JsonNode s : suggestions) {
            JsonNode transfer = post("/v1/group/" + group.id() + "/settlements", carol.token(), Map.of(
                    "debtorMemberId", s.get("debtorMemberId").asLong(),
                    "creditorMemberId", s.get("creditorMemberId").asLong(),
                    "amount", s.get("amount").decimalValue())).expect(201).body();
            assertThat(transfer.get("type").asText()).isEqualTo("TRANSFER");
            assertThat(transfer.get("category").isNull()).isTrue();
        }

        assertThat(get("/v1/group/" + group.id() + "/debt-settlement", carol.token()).expect(200).body()).isEmpty();
        balances(group, alice).values().forEach(v -> assertThat(v).isEqualByComparingTo("0"));

        // transfers don't count as spending
        JsonNode sums = get("/v1/group/" + group.id() + "/sum-by-category", alice.token()).expect(200).body();
        assertThat(sums).hasSize(1);
        assertThat(sums.get(0).get("category").asText()).isEqualTo("Food");
        assertThat(sums.get(0).get("sum").decimalValue()).isEqualByComparingTo("120.00");
    }

    @Test
    void transferValidation() throws Exception {
        TestUser alice = register("Alice");
        TestGroup group = createGroup(alice, "Solo");
        var toSelf = post("/v1/group/" + group.id() + "/settlements", alice.token(), Map.of(
                "debtorMemberId", group.ownerMemberId(), "creditorMemberId", group.ownerMemberId(), "amount", 5))
                .expect(400);
        assertThat(toSelf.errorCode()).isEqualTo("RB_103");
    }

    @Test
    void categoriesAreReusedCaseInsensitively() throws Exception {
        TestUser alice = register("Alice");
        TestGroup group = createGroup(alice, "Cats");
        long a = group.ownerMemberId();
        for (String category : List.of("Food", "food ", "Transport")) {
            post("/v1/group/" + group.id() + "/expenses", alice.token(), Map.of(
                    "splitType", "EQUAL", "description", "x", "amount", 1, "category", category,
                    "payerMemberId", a, "shares", List.of(Map.of("memberId", a)))).expect(201);
        }
        JsonNode categories = get("/v1/group/" + group.id() + "/categories", alice.token()).expect(200).body();
        assertThat(categories).hasSize(2);
        assertThat(categories.get(0).get("name").asText()).isEqualTo("Transport");
    }

    @Test
    void personalExpenses() throws Exception {
        TestUser alice = register("Alice");
        JsonNode created = post("/v1/personal/expenses", alice.token(), Map.of(
                "amount", "12.50", "description", "Coffee", "category", "Food")).expect(201).body();
        long id = created.get("id").asLong();

        JsonNode updated = put("/v1/personal/expenses/" + id, alice.token(), Map.of(
                "amount", "13.00", "description", "Coffee", "category", "Food", "version", 0)).expect(200).body();
        assertThat(updated.get("version").asLong()).isEqualTo(1);

        JsonNode page = get("/v1/personal/expenses", alice.token()).expect(200).body();
        assertThat(page.get("content")).hasSize(1);
        assertThat(page.get("content").get(0).get("amount").decimalValue()).isEqualByComparingTo("13.00");

        JsonNode sums = get("/v1/personal/sum-by-category", alice.token()).expect(200).body();
        assertThat(sums.get(0).get("sum").decimalValue()).isEqualByComparingTo("13.00");
        assertThat(get("/v1/personal/categories", alice.token()).expect(200).body()).hasSize(1);

        delete("/v1/personal/expenses/" + id + "?version=1", alice.token()).expect(204);
        assertThat(get("/v1/personal/expenses", alice.token()).expect(200).body().get("totalElements").asLong()).isZero();

        // the personal group never shows up as a shared group
        assertThat(get("/v1/user/groups", alice.token()).expect(200).body()).isEmpty();
    }

    @Test
    void pageSizeIsCapped() throws Exception {
        TestUser alice = register("Alice");
        TestGroup group = createGroup(alice, "Paging");
        JsonNode page = get("/v1/group/" + group.id() + "/expenses?size=5000", alice.token()).expect(200).body();
        assertThat(page.get("size").asInt()).isEqualTo(100);
    }
}
