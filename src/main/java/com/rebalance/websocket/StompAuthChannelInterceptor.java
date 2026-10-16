package com.rebalance.websocket;

import com.rebalance.entity.User;
import com.rebalance.security.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Authenticates the STOMP CONNECT frame with the same JWT as the REST API and only allows subscribing to the
 * user's own queue. Rejected frames make the broker send an ERROR frame and close the session.
 */
@Component
@RequiredArgsConstructor
public class StompAuthChannelInterceptor implements ChannelInterceptor {
    private static final String BEARER = "Bearer ";
    private static final String USER_QUEUE_PREFIX = "/user/queue/";

    private final JwtService jwtService;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }

        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            String header = accessor.getFirstNativeHeader("Authorization");
            if (header == null || !header.startsWith(BEARER)) {
                throw new MessagingException("Unauthorized");
            }
            User user = jwtService.authenticate(header.substring(BEARER.length()).trim())
                    .orElseThrow(() -> new MessagingException("Unauthorized"));
            accessor.setUser(new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
        } else if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            String destination = accessor.getDestination();
            if (accessor.getUser() == null || destination == null || !destination.startsWith(USER_QUEUE_PREFIX)) {
                throw new MessagingException("Forbidden subscription");
            }
        } else if (StompCommand.SEND.equals(accessor.getCommand())) {
            // the server doesn't accept client messages
            throw new MessagingException("Sending is not supported");
        }
        return message;
    }
}
