package com.rebalance.it;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AuthIT extends IntegrationTestBase {
    @Test
    void registerLoginAndInfo() throws Exception {
        TestUser alice = register("Alice");

        // emails are case-insensitive
        String token = post("/v1/user/login", null, Map.of("email", alice.email().toUpperCase(), "password", PASSWORD))
                .expect(200).body().get("token").asText();

        var info = get("/v1/user/info", token).expect(200).body();
        assertThat(info.get("email").asText()).isEqualTo(alice.email());
        assertThat(info.get("nickname").asText()).isEqualTo("Alice");
        assertThat(info.get("currency").asText()).isEqualTo("PLN");
        assertThat(info.get("personalGroupId").asLong()).isPositive();
        assertThat(info.has("password")).isFalse();
    }

    @Test
    void duplicateEmailIsRejected() throws Exception {
        TestUser alice = register("Alice");
        var response = post("/v1/user/register", null, Map.of(
                "email", alice.email(), "password", PASSWORD, "nickname", "Other", "currency", "EUR")).expect(409);
        assertThat(response.errorCode()).isEqualTo("RB_001");
    }

    @Test
    void weakPasswordIsRejected() throws Exception {
        var response = post("/v1/user/register", null, Map.of(
                "email", "weak-" + UUID.randomUUID() + "@example.com", "password", "password",
                "nickname", "Weak", "currency", "EUR")).expect(400);
        assertThat(response.errorCode()).isEqualTo("RB_998");
    }

    @Test
    void wrongPasswordIs401AndUnknownEmailLooksTheSame() throws Exception {
        TestUser alice = register("Alice");
        assertThat(post("/v1/user/login", null, Map.of("email", alice.email(), "password", "Wrong-123"))
                .expect(401).errorCode()).isEqualTo("RB_402");
        assertThat(post("/v1/user/login", null, Map.of("email", "nobody-" + UUID.randomUUID() + "@example.com", "password", "Wrong-123"))
                .expect(401).errorCode()).isEqualTo("RB_402");
    }

    @Test
    void sixthFailedLoginIsRateLimited() throws Exception {
        TestUser alice = register("Alice");
        for (int i = 0; i < 5; i++) {
            post("/v1/user/login", null, Map.of("email", alice.email(), "password", "Wrong-123")).expect(401);
        }
        assertThat(post("/v1/user/login", null, Map.of("email", alice.email(), "password", PASSWORD))
                .expect(429).errorCode()).isEqualTo("RB_004");
    }

    @Test
    void invalidTokenGives401ProblemWithoutBasicChallenge() throws Exception {
        var response = get("/v1/user/info", "not-a-jwt").expect(401);
        assertThat(response.errorCode()).isEqualTo("RB_401");
        assertThat(response.raw().getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE)).isNull();
        assertThat(response.raw().getResponse().getContentType()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);

        get("/v1/user/info", null).expect(401);
    }

    @Test
    void basicAuthIsNotAccepted() throws Exception {
        TestUser alice = register("Alice");
        String basic = java.util.Base64.getEncoder().encodeToString((alice.email() + ":" + PASSWORD).getBytes());
        int status = mvc.perform(MockMvcRequestBuilders.get("/v1/user/info")
                .header(HttpHeaders.AUTHORIZATION, "Basic " + basic)).andReturn().getResponse().getStatus();
        assertThat(status).isEqualTo(401);
    }

    @Test
    void logoutRevokesTheToken() throws Exception {
        TestUser alice = register("Alice");
        post("/v1/user/logout", alice.token(), null).expect(204);
        get("/v1/user/info", alice.token()).expect(401);
    }

    @Test
    void changePasswordKeepsThisSessionAndRevokesOthers() throws Exception {
        TestUser alice = register("Alice");
        String otherSession = post("/v1/user/login", null, Map.of("email", alice.email(), "password", PASSWORD))
                .expect(200).body().get("token").asText();

        assertThat(put("/v1/user/password", alice.token(), Map.of("currentPassword", "Wrong-123", "newPassword", "Newer-456"))
                .expect(400).errorCode()).isEqualTo("RB_003");
        put("/v1/user/password", alice.token(), Map.of("currentPassword", PASSWORD, "newPassword", "Newer-456")).expect(204);

        get("/v1/user/info", alice.token()).expect(200);
        get("/v1/user/info", otherSession).expect(401);
        post("/v1/user/login", null, Map.of("email", alice.email(), "password", PASSWORD)).expect(401);
        post("/v1/user/login", null, Map.of("email", alice.email(), "password", "Newer-456")).expect(200);
    }

    @Test
    void healthCheckIsPublicAndUnknownPathsAreDenied() throws Exception {
        get("/v1/connect/test", null).expect(200);
        get("/actuator", null).expect(401);
        assertThat(call(MockMvcRequestBuilders.post("/v1/nope"), register("Bob").token(), null).status()).isIn(403, 404);
    }
}
