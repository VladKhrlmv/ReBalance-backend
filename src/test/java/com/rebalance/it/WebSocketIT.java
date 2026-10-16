package com.rebalance.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.*;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.testcontainers.containers.MySQLContainer;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real server + real STOMP client: notifications reach the right user only, and only with a valid token.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebSocketIT {
    @ServiceConnection
    static final MySQLContainer<?> MYSQL = IntegrationTestBase.MYSQL;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.jwt.secret", () -> "aW50ZWdyYXRpb24tdGVzdC1vbmx5LXNlY3JldC1rZXktMTIzNDU2Nzg5MA==");
        registry.add("app.storage.path", IntegrationTestBase.IMAGES::toString);
    }

    @LocalServerPort
    int port;
    @Autowired
    ObjectMapper objectMapper;

    private RestClient http() {
        return RestClient.builder().baseUrl("http://localhost:" + port + "/v1").build();
    }

    private JsonNode register(String nickname) {
        return http().post().uri("/user/register").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", nickname.toLowerCase() + "-" + UUID.randomUUID() + "@example.com",
                        "password", IntegrationTestBase.PASSWORD, "nickname", nickname, "currency", "EUR"))
                .retrieve().body(JsonNode.class);
    }

    private StompSession connect(String token, BlockingQueue<JsonNode> received) throws Exception {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        // the server sends application/json frames
        client.setMessageConverter(new MappingJackson2MessageConverter());
        StompHeaders connectHeaders = new StompHeaders();
        if (token != null) {
            connectHeaders.add("Authorization", "Bearer " + token);
        }
        StompSession session = client.connectAsync("ws://localhost:" + port + "/v1/ws", (org.springframework.web.socket.WebSocketHttpHeaders) null,
                connectHeaders, new StompSessionHandlerAdapter() {
                }).get(10, TimeUnit.SECONDS);
        session.subscribe("/user/queue/notifications", new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return JsonNode.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                received.add((JsonNode) payload);
            }
        });
        return session;
    }

    @Test
    void notificationIsPushedToOtherMembersAfterCommit() throws Exception {
        JsonNode alice = register("Alice");
        JsonNode bob = register("Bob");
        String aliceToken = alice.get("token").asText();
        String bobToken = bob.get("token").asText();

        JsonNode group = http().post().uri("/group").header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken)
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("name", "Live", "currency", "EUR"))
                .retrieve().body(JsonNode.class);
        JsonNode joined = http().post().uri("/invites/" + group.get("inviteToken").asText() + "/join")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + bobToken).retrieve().body(JsonNode.class);

        BlockingQueue<JsonNode> bobReceived = new LinkedBlockingQueue<>();
        BlockingQueue<JsonNode> aliceReceived = new LinkedBlockingQueue<>();
        StompSession bobSession = connect(bobToken, bobReceived);
        StompSession aliceSession = connect(aliceToken, aliceReceived);
        Thread.sleep(500); // let the subscriptions register

        long a = group.get("myMemberId").asLong();
        long b = joined.get("myMemberId").asLong();
        http().post().uri("/group/" + group.get("id").asLong() + "/expenses")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("splitType", "EQUAL", "description", "Pizza", "amount", "30.00", "payerMemberId", a,
                        "shares", List.of(Map.of("memberId", a), Map.of("memberId", b))))
                .retrieve().toBodilessEntity();

        JsonNode notification = bobReceived.poll(10, TimeUnit.SECONDS);
        assertThat(notification).isNotNull();
        assertThat(notification.get("type").asText()).isEqualTo("GroupExpenseAdded");
        assertThat(notification.get("groupId").asLong()).isEqualTo(group.get("id").asLong());

        // the actor gets nothing
        assertThat(aliceReceived.poll(1, TimeUnit.SECONDS)).isNull();

        bobSession.disconnect();
        aliceSession.disconnect();
    }

    @Test
    void connectWithoutValidTokenIsRejected() {
        assertThatThrownBy(() -> {
            StompSession session = connect("garbage", new LinkedBlockingQueue<>());
            // the server answers CONNECT with an ERROR frame and closes the connection
            Thread.sleep(1000);
            if (!session.isConnected()) {
                throw new IllegalStateException("disconnected");
            }
        }).isNotNull();
    }
}
