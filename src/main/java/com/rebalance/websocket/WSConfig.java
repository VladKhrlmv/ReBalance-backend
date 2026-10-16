package com.rebalance.websocket;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
@ConditionalOnWebApplication
public class WSConfig implements WebSocketMessageBrokerConfigurer {
    public static final String ENDPOINT = "/v1/ws";

    private final StompAuthChannelInterceptor authInterceptor;
    private TaskScheduler heartbeatScheduler;

    public WSConfig(StompAuthChannelInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    @Autowired
    public void setHeartbeatScheduler(@Lazy @Qualifier("messageBrokerTaskScheduler") TaskScheduler messageBrokerTaskScheduler) {
        this.heartbeatScheduler = messageBrokerTaskScheduler;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // heartbeats keep idle connections alive through proxies and NAT
        registry.enableSimpleBroker("/queue")
                .setHeartbeatValue(new long[]{20000, 20000})
                .setTaskScheduler(heartbeatScheduler);
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Origin checks protect cookie-authenticated sockets from cross-site hijacking. Here nothing is granted by the
        // handshake itself: the STOMP CONNECT frame must carry the user's bearer token (StompAuthChannelInterceptor),
        // which another site can't obtain. Spring's same-origin check is also unreliable behind the reverse proxy
        // (it compares the browser's Origin with the host/port it reconstructs), so every origin is allowed.
        registry.addEndpoint(ENDPOINT).setAllowedOriginPatterns("*");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(authInterceptor);
    }
}
