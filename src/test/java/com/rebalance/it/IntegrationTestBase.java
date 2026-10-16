package com.rebalance.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.MySQLContainer;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the whole application against a real MySQL (Testcontainers, so Docker must be running) with Flyway and
 * ddl-auto=validate, exactly like production. All IT classes share one container and one Spring context; every test
 * creates its own users and groups, so no cleanup is needed.
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class IntegrationTestBase {
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    static final Path IMAGES;

    static {
        MYSQL.start();
        try {
            IMAGES = Files.createTempDirectory("rebalance-it-images");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static final String PASSWORD = "Secret-123";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.jwt.secret", () -> "aW50ZWdyYXRpb24tdGVzdC1vbmx5LXNlY3JldC1rZXktMTIzNDU2Nzg5MA==");
        registry.add("app.storage.path", IMAGES::toString);
    }

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected ObjectMapper objectMapper;

    // ----- request helpers -----

    protected record Response(int status, JsonNode body, MvcResult raw) {
        public Response expect(int expected) {
            assertThat(status).as("HTTP status, body: %s", body).isEqualTo(expected);
            return this;
        }

        public String errorCode() {
            return body == null ? null : body.path("title").asText(null);
        }
    }

    protected Response call(MockHttpServletRequestBuilder request, String token, Object body) throws Exception {
        if (token != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsBytes(body));
        }
        // every call comes from a different address, so per-IP rate limits don't interfere between tests
        request.with(r -> {
            r.setRemoteAddr(randomIp());
            return r;
        });
        MvcResult result = mvc.perform(request).andReturn();
        byte[] content = result.getResponse().getContentAsByteArray();
        String type = result.getResponse().getContentType();
        JsonNode json = content.length > 0 && type != null && type.contains("json") ? objectMapper.readTree(content) : null;
        return new Response(result.getResponse().getStatus(), json, result);
    }

    protected Response get(String url, String token) throws Exception {
        return call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url), token, null);
    }

    protected Response post(String url, String token, Object body) throws Exception {
        return call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url), token, body);
    }

    protected Response put(String url, String token, Object body) throws Exception {
        return call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(url), token, body);
    }

    protected Response patch(String url, String token, Object body) throws Exception {
        return call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(url), token, body);
    }

    protected Response delete(String url, String token) throws Exception {
        return call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(url), token, null);
    }

    // ----- domain helpers -----

    protected record TestUser(long id, String email, String nickname, String token) {
    }

    protected record TestGroup(long id, String inviteToken, long ownerMemberId) {
    }

    protected TestUser register(String nickname) throws Exception {
        String email = nickname.toLowerCase() + "-" + UUID.randomUUID() + "@example.com";
        JsonNode body = post("/v1/user/register", null, Map.of(
                "email", email, "password", PASSWORD, "nickname", nickname, "currency", "PLN"))
                .expect(201).body();
        return new TestUser(body.get("id").asLong(), email, nickname, body.get("token").asText());
    }

    protected TestGroup createGroup(TestUser owner, String name) throws Exception {
        JsonNode body = post("/v1/group", owner.token(), Map.of("name", name, "currency", "PLN")).expect(201).body();
        return new TestGroup(body.get("id").asLong(), body.get("inviteToken").asText(), body.get("myMemberId").asLong());
    }

    /** Joins through the invite link and returns the new member id. */
    protected long join(TestGroup group, TestUser user) throws Exception {
        return post("/v1/invites/" + group.inviteToken() + "/join", user.token(), null).expect(200)
                .body().get("myMemberId").asLong();
    }

    protected JsonNode addEqualExpense(TestGroup group, TestUser actor, String amount, long payerMemberId,
                                       List<Long> participantMemberIds) throws Exception {
        return post("/v1/group/" + group.id() + "/expenses", actor.token(), Map.of(
                "splitType", "EQUAL",
                "description", "Dinner",
                "amount", amount,
                "category", "Food",
                "payerMemberId", payerMemberId,
                "shares", participantMemberIds.stream().map(id -> Map.of("memberId", id)).toList()))
                .expect(201).body();
    }

    /** memberId → balance; compare with isEqualByComparingTo. */
    protected Map<Long, BigDecimal> balances(TestGroup group, TestUser user) throws Exception {
        JsonNode members = get("/v1/group/" + group.id() + "/members", user.token()).expect(200).body();
        Map<Long, BigDecimal> result = new HashMap<>();
        members.forEach(m -> result.put(m.get("memberId").asLong(), m.get("balance").decimalValue()));
        return result;
    }

    protected static String randomIp() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return "10." + random.nextInt(256) + "." + random.nextInt(256) + "." + (1 + random.nextInt(254));
    }
}
