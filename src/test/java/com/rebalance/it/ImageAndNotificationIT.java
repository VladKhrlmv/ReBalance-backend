package com.rebalance.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.rebalance.service.image.ImageProcessorTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ImageAndNotificationIT extends IntegrationTestBase {
    private Response upload(long expenseId, TestUser user, byte[] content) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "receipt", "application/octet-stream", content);
        return call(MockMvcRequestBuilders.multipart(HttpMethod.PUT, "/v1/expenses/" + expenseId + "/image").file(file),
                user.token(), null);
    }

    @Test
    void uploadReplaceDownloadAndDeleteImage() throws Exception {
        TestUser alice = register("Alice");
        TestGroup group = createGroup(alice, "Receipts");
        long a = group.ownerMemberId();
        long expenseId = addEqualExpense(group, alice, "10.00", a, List.of(a)).get("id").asLong();

        assertThat(upload(expenseId, alice, "not an image".getBytes()).expect(400).errorCode()).isEqualTo("RB_303");

        upload(expenseId, alice, ImageProcessorTest.png(3000, 3000)).expect(204);
        upload(expenseId, alice, ImageProcessorTest.png(400, 300)).expect(204);

        Response image = get("/v1/expenses/" + expenseId + "/image", alice.token()).expect(200);
        assertThat(image.raw().getResponse().getContentType()).isEqualTo("image/jpeg");
        assertThat(image.raw().getResponse().getContentAsByteArray()).isNotEmpty();

        JsonNode expense = get("/v1/group/" + group.id() + "/expenses/" + expenseId, alice.token()).expect(200).body();
        assertThat(expense.get("hasImage").asBoolean()).isTrue();
        JsonNode thumbnails = post("/v1/images/thumbnails", alice.token(), Map.of("expenseIds", List.of(expenseId, 999999)))
                .expect(200).body();
        assertThat(thumbnails).hasSize(1);
        assertThat(thumbnails.get(0).get("expenseId").asLong()).isEqualTo(expenseId);

        // only the current image's two files are kept
        try (var files = java.nio.file.Files.list(IMAGES)) {
            assertThat(files.filter(p -> p.getFileName().toString().startsWith(expenseId + "-")).count()).isEqualTo(2);
        }

        delete("/v1/expenses/" + expenseId + "/image", alice.token()).expect(204);
        get("/v1/expenses/" + expenseId + "/image", alice.token()).expect(404);
    }

    @Test
    void membersAreNotifiedButNotTheActor() throws Exception {
        TestUser alice = register("Alice");
        TestUser bob = register("Bob");
        TestGroup group = createGroup(alice, "Notified");
        long a = group.ownerMemberId();
        long b = join(group, bob);

        // Alice hears that Bob joined
        JsonNode aliceUnseen = get("/v1/notifications/unseen", alice.token()).expect(200).body();
        assertThat(aliceUnseen).hasSize(1);
        assertThat(aliceUnseen.get(0).get("type").asText()).isEqualTo("MemberJoined");
        assertThat(aliceUnseen.get(0).get("message").asText()).isEqualTo("Bob joined Notified");

        addEqualExpense(group, alice, "45.00", a, List.of(a, b));

        JsonNode bobUnseen = get("/v1/notifications/unseen", bob.token()).expect(200).body();
        assertThat(bobUnseen).hasSize(1);
        JsonNode notification = bobUnseen.get(0);
        assertThat(notification.get("type").asText()).isEqualTo("GroupExpenseAdded");
        assertThat(notification.get("groupId").asLong()).isEqualTo(group.id());
        assertThat(notification.get("actorNickname").asText()).isEqualTo("Alice");
        assertThat(notification.get("message").asText()).isEqualTo("Alice added \"Dinner\" (45.00 PLN) in Notified");

        // the actor isn't notified about their own expense
        assertThat(get("/v1/notifications/unseen", alice.token()).expect(200).body()).hasSize(1);

        post("/v1/notifications/seen", bob.token(), Map.of("ids", List.of(notification.get("id").asLong()))).expect(204);
        assertThat(get("/v1/notifications/unseen", bob.token()).expect(200).body()).isEmpty();
    }
}
