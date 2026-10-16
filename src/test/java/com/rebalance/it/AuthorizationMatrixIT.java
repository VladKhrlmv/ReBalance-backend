package com.rebalance.it;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every group-scoped endpoint, called by an outsider (must look like the group doesn't exist), by a plain member and
 * by the owner.
 */
class AuthorizationMatrixIT extends IntegrationTestBase {
    private TestUser owner;
    private TestUser member;
    private TestUser outsider;
    private TestGroup group;
    private long ownerMemberId;
    private long memberId;
    private long expenseId;
    private String g;

    @BeforeEach
    void setUp() throws Exception {
        owner = register("Owner");
        member = register("Member");
        outsider = register("Outsider");
        group = createGroup(owner, "Secret group");
        ownerMemberId = group.ownerMemberId();
        memberId = join(group, member);
        JsonNode expense = addEqualExpense(group, owner, "10.00", ownerMemberId, List.of(ownerMemberId, memberId));
        expenseId = expense.get("id").asLong();
        g = "/v1/group/" + group.id();
        uploadImage(owner).expect(204);
    }

    private Response uploadImage(TestUser user) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "receipt.png", "image/png",
                com.rebalance.service.image.ImageProcessorTest.png(50, 50));
        return call(MockMvcRequestBuilders.multipart("/v1/expenses/" + expenseId + "/image").file(file)
                .with(r -> {
                    r.setMethod("PUT");
                    return r;
                }), user.token(), null);
    }

    private Map<String, Object> expenseBody(long version) {
        return Map.of("splitType", "EQUAL", "description", "Changed", "amount", "20.00", "payerMemberId", ownerMemberId,
                "shares", List.of(Map.of("memberId", ownerMemberId)), "version", version);
    }

    @Test
    void outsiderSeesNothing() throws Exception {
        String t = outsider.token();
        List<Response> responses = List.of(
                get(g, t),
                patch(g, t, Map.of("name", "Hacked")),
                delete(g, t),
                put(g + "/favorite", t, Map.of("favorite", true)),
                get(g + "/members", t),
                post(g + "/leave", t, null),
                delete(g + "/members/" + memberId, t),
                get(g + "/debt-settlement", t),
                post(g + "/settlements", t, Map.of("debtorMemberId", memberId, "creditorMemberId", ownerMemberId, "amount", 5)),
                post(g + "/invite/rotate", t, null),
                put(g + "/invite", t, Map.of("enabled", false)),
                get(g + "/expenses", t),
                get(g + "/expenses/" + expenseId, t),
                post(g + "/expenses", t, expenseBody(0)),
                put(g + "/expenses/" + expenseId, t, expenseBody(0)),
                delete(g + "/expenses/" + expenseId + "?version=0", t),
                get(g + "/categories", t),
                get(g + "/sum-by-category", t),
                get("/v1/expenses/" + expenseId + "/image", t),
                delete("/v1/expenses/" + expenseId + "/image", t),
                uploadImage(outsider));
        for (Response response : responses) {
            assertThat(response.status()).as("outsider response %s", response.body()).isEqualTo(404);
        }

        // batch thumbnails silently leave out what the caller can't see
        assertThat(post("/v1/images/thumbnails", t, Map.of("expenseIds", List.of(expenseId))).expect(200).body()).isEmpty();
        // the outsider's group list doesn't include the group
        assertThat(get("/v1/user/groups", t).expect(200).body()).isEmpty();
        // nothing was changed
        assertThat(get(g, owner.token()).expect(200).body().get("name").asText()).isEqualTo("Secret group");
        assertThat(get(g + "/expenses/" + expenseId, owner.token()).expect(200).body().get("version").asLong()).isZero();
    }

    @Test
    void memberCanUseTheGroupButNotOwnerActions() throws Exception {
        String t = member.token();
        get(g, t).expect(200);
        get(g + "/members", t).expect(200);
        get(g + "/debt-settlement", t).expect(200);
        get(g + "/expenses", t).expect(200);
        get(g + "/categories", t).expect(200);
        get(g + "/sum-by-category", t).expect(200);
        get("/v1/expenses/" + expenseId + "/image", t).expect(200);
        assertThat(post("/v1/images/thumbnails", t, Map.of("expenseIds", List.of(expenseId))).expect(200).body()).hasSize(1);
        put(g + "/favorite", t, Map.of("favorite", true)).expect(204);
        patch(g, t, Map.of("name", "Renamed by member")).expect(200);

        for (Response response : List.of(
                delete(g, t),
                delete(g + "/members/" + ownerMemberId, t),
                post(g + "/invite/rotate", t, null),
                put(g + "/invite", t, Map.of("enabled", false)))) {
            assertThat(response.status()).as("member response %s", response.body()).isEqualTo(403);
            assertThat(response.errorCode()).isEqualTo("RB_202");
        }

        // any member may edit and delete expenses
        put(g + "/expenses/" + expenseId, t, expenseBody(0)).expect(200);
    }

    @Test
    void memberResponsesNeverContainEmails() throws Exception {
        JsonNode members = get(g + "/members", member.token()).expect(200).body();
        assertThat(members.toString()).doesNotContain("@example.com");
        assertThat(members).hasSize(2);
        JsonNode ownerRow = members.get(0);
        assertThat(ownerRow.get("owner").asBoolean()).isTrue();
        assertThat(ownerRow.get("nickname").asText()).isEqualTo("Owner");
    }

    @Test
    void personalExpensesArePrivate() throws Exception {
        JsonNode personal = post("/v1/personal/expenses", owner.token(), Map.of("amount", 5, "description", "Private"))
                .expect(201).body();
        long id = personal.get("id").asLong();
        put("/v1/personal/expenses/" + id, outsider.token(), Map.of("amount", 1, "description", "Hacked", "version", 0)).expect(404);
        delete("/v1/personal/expenses/" + id + "?version=0", outsider.token()).expect(404);
        get("/v1/expenses/" + id + "/image", outsider.token()).expect(404);
        assertThat(get("/v1/personal/expenses", outsider.token()).expect(200).body().get("totalElements").asLong()).isZero();

        // the personal group can't be reached through the group endpoints, not even by its owner
        long personalGroupId = get("/v1/user/info", owner.token()).expect(200).body().get("personalGroupId").asLong();
        get("/v1/group/" + personalGroupId, owner.token()).expect(404);
        get("/v1/group/" + personalGroupId + "/sum-by-category", outsider.token()).expect(404);
    }
}
