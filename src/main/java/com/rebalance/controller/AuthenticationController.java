package com.rebalance.controller;

import com.rebalance.dto.request.LoginRequest;
import com.rebalance.dto.request.RegisterRequest;
import com.rebalance.dto.response.TokenResponse;
import com.rebalance.dto.response.UserWithTokenResponse;
import com.rebalance.service.AuthenticationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Authentication management")
@RequiredArgsConstructor
@RestController
@RequestMapping(APIVersion.current + "/user")
public class AuthenticationController {
    private final AuthenticationService authenticationService;

    @Operation(summary = "Register new user")
    @PostMapping("/register")
    public ResponseEntity<UserWithTokenResponse> register(@RequestBody @Validated RegisterRequest request,
                                                          HttpServletRequest httpRequest) {
        return new ResponseEntity<>(authenticationService.register(request, httpRequest.getRemoteAddr()), HttpStatus.CREATED);
    }

    @Operation(summary = "Login user (get new JWT)")
    @PostMapping("/login")
    public TokenResponse login(@RequestBody @Validated LoginRequest request, HttpServletRequest httpRequest) {
        return authenticationService.login(request, httpRequest.getRemoteAddr());
    }

    @Operation(summary = "Logout user (revoke this JWT)")
    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        authenticationService.logout();
        return ResponseEntity.noContent().build();
    }
}
