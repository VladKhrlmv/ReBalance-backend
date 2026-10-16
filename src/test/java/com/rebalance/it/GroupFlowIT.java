package com.rebalance.it;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GroupFlowIT extends IntegrationTestBase {
    @Test
    void invitePreviewIsPublicAndJoiningIsIdempotent() throws Exception {
        TestUser alice = register("Alice");
        TestUser bob = register("Bob");
        TestGroup group = createGroup(alice, "Ski trip");

        JsonNode preview = get("/v1/invites/" + group.inviteToken(), null).expect(200).body();
        assertThat(preview.get("name").asText()).isEqualTo("Ski trip");
        assertThat(preview.get("memberCount").asLong()).isEqualTo(1);
        assertThat(preview.get("alreadyMember").asBoolean()).isFalse();
        assertThat(get("/v1/invites/" + group.inviteToken(), alice.token()).expect(200).body().get("alreadyMember").asBoolean()).isTrue();

        long first = join(group, bob);
        long second = join(group, bob);
        assertThat(second).isEqualTo(first);
        assertThat(get("/v1/group/" + group.id(), bob.token()).expect(200).body().get("memberCount").asLong()).isEqualTo(2);

        assertThat(get("/v1/invites/unknown-token", null).expect(404).errorCode()).isEqualTo("RB_206");
        post("/v1/invites/" + group.inviteToken() + "/join", null, null).expect(401);
    }

    @Test
    void ownerCanRotateAndDisableTheLink() throws Exception {
        TestUser alice = register("Alice");
        TestUser bob = register("Bob");
        TestGroup group = createGroup(alice, "Rotating");

        JsonNode rotated = post("/v1/group/" + group.id() + "/invite/rotate", alice.token(), null).expect(200).body();
        String newToken = rotated.get("inviteToken").asText();
        assertThat(newToken).isNotEqualTo(group.inviteToken()).hasSize(22);
        get("/v1/invites/" + group.inviteToken(), null).expect(404);

        put("/v1/group/" + group.id() + "/invite", alice.token(), Map.of("enabled", false)).expect(200);
        post("/v1/invites/" + newToken + "/join", bob.token(), null).expect(404);

        put("/v1/group/" + group.id() + "/invite", alice.token(), Map.of("enabled", true)).expect(200);
        post("/v1/invites/" + newToken + "/join", bob.token(), null).expect(200);
    }

    @Test
    void leavingRequiresZeroBalanceAndOwnershipPassesOn() throws Exception {
        TestUser alice = register("Alice");
        TestUser bob = register("Bob");
        TestUser carol = register("Carol");
        TestGroup group = createGroup(alice, "Leavers");
        long a = group.ownerMemberId();
        long b = join(group, bob);
        long c = join(group, carol);
        addEqualExpense(group, bob, "20.00", b, List.of(a, b));

        assertThat(post("/v1/group/" + group.id() + "/leave", alice.token(), null).expect(409).errorCode()).isEqualTo("RB_203");

        post("/v1/group/" + group.id() + "/settlements", alice.token(),
                Map.of("debtorMemberId", a, "creditorMemberId", b, "amount", "10.00")).expect(201);
        post("/v1/group/" + group.id() + "/leave", alice.token(), null).expect(204);

        // Alice is gone, Bob (longest-standing remaining member) owns the group now
        get("/v1/group/" + group.id(), alice.token()).expect(404);
        JsonNode bobsView = get("/v1/group/" + group.id(), bob.token()).expect(200).body();
        assertThat(bobsView.get("owner").asBoolean()).isTrue();
        assertThat(bobsView.get("memberCount").asLong()).isEqualTo(2);

        // the history still shows Alice
        JsonNode members = get("/v1/group/" + group.id() + "/members", carol.token()).expect(200).body();
        assertThat(members).hasSize(3);
        assertThat(members.get(0).get("status").asText()).isEqualTo("LEFT");

        // former members can't be used in new expenses
        assertThat(post("/v1/group/" + group.id() + "/expenses", carol.token(), Map.of(
                "splitType", "EQUAL", "description", "x", "amount", 5, "payerMemberId", c,
                "shares", List.of(Map.of("memberId", a)))).expect(400).errorCode()).isEqualTo("RB_104");

        // and rejoining through the link reactivates the same member
        assertThat(join(group, alice)).isEqualTo(a);
    }

    @Test
    void lastMemberLeavingDeletesTheGroup() throws Exception {
        TestUser alice = register("Alice");
        TestGroup group = createGroup(alice, "Alone");
        post("/v1/group/" + group.id() + "/leave", alice.token(), null).expect(204);
        get("/v1/invites/" + group.inviteToken(), null).expect(404);
        assertThat(get("/v1/user/groups", alice.token()).expect(200).body()).isEmpty();
    }

    @Test
    void ownerRemovesMembersWithZeroBalanceOnly() throws Exception {
        TestUser alice = register("Alice");
        TestUser bob = register("Bob");
        TestUser carol = register("Carol");
        TestGroup group = createGroup(alice, "Removals");
        long a = group.ownerMemberId();
        long b = join(group, bob);
        long c = join(group, carol);
        addEqualExpense(group, alice, "10.00", a, List.of(a, b));

        String members = "/v1/group/" + group.id() + "/members/";
        assertThat(delete(members + b, alice.token()).expect(409).errorCode()).isEqualTo("RB_203");
        assertThat(delete(members + a, alice.token()).expect(400).errorCode()).isEqualTo("RB_208");
        delete(members + c, alice.token()).expect(204);
        get("/v1/group/" + group.id(), carol.token()).expect(404);
        delete(members + c, alice.token()).expect(404);
    }

    @Test
    void ownerDeletesGroup() throws Exception {
        TestUser alice = register("Alice");
        TestUser bob = register("Bob");
        TestGroup group = createGroup(alice, "Doomed");
        join(group, bob);

        delete("/v1/group/" + group.id(), alice.token()).expect(204);
        get("/v1/group/" + group.id(), bob.token()).expect(404);
        assertThat(get("/v1/user/groups", bob.token()).expect(200).body()).isEmpty();
    }

    @Test
    void groupsListIsSortedFavoritesFirst() throws Exception {
        TestUser alice = register("Alice");
        createGroup(alice, "b group");
        TestGroup second = createGroup(alice, "a group");
        TestGroup third = createGroup(alice, "c group");
        put("/v1/group/" + third.id() + "/favorite", alice.token(), Map.of("favorite", true)).expect(204);

        JsonNode groups = get("/v1/user/groups", alice.token()).expect(200).body();
        assertThat(groups).extracting(n -> n.get("name").asText()).containsExactly("c group", "a group", "b group");
        assertThat(groups.get(0).get("favorite").asBoolean()).isTrue();
        assertThat(second.id()).isPositive();
    }

    @Test
    void validation() throws Exception {
        TestUser alice = register("Alice");
        assertThat(post("/v1/group", alice.token(), Map.of("name", "", "currency", "PLN")).expect(400).errorCode()).isEqualTo("RB_998");
        assertThat(post("/v1/group", alice.token(), Map.of("name", "x", "currency", "zloty")).expect(400).errorCode()).isEqualTo("RB_998");
        assertThat(post("/v1/group", alice.token(), "{not json").status()).isEqualTo(400);
    }
}
