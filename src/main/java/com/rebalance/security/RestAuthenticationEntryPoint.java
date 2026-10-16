package com.rebalance.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rebalance.exception.RebalanceErrorType;
import com.rebalance.exception.RebalanceExceptionHandler;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Answers 401/403 as problem+json (never a Basic-auth challenge, which would pop up a browser login dialog).
 */
@Component
@RequiredArgsConstructor
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint, AccessDeniedHandler {
    private final ObjectMapper objectMapper;

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
            throws IOException {
        write(response, RebalanceErrorType.RB_401);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException)
            throws IOException {
        write(response, RebalanceErrorType.RB_202);
    }

    private void write(HttpServletResponse response, RebalanceErrorType errorType) throws IOException {
        response.setStatus(errorType.getStatus().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(),
                RebalanceExceptionHandler.problem(errorType, errorType.getDescription()));
    }
}
