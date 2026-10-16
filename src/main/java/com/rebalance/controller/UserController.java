package com.rebalance.controller;

import com.rebalance.dto.request.ChangePasswordRequest;
import com.rebalance.dto.response.GroupResponse;
import com.rebalance.dto.response.UserResponse;
import com.rebalance.service.AuthenticationService;
import com.rebalance.service.GroupService;
import com.rebalance.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "User management")
@RequiredArgsConstructor
@RestController
@RequestMapping(APIVersion.current + "/user")
public class UserController {
    private final UserService userService;
    private final GroupService groupService;
    private final AuthenticationService authenticationService;

    @Operation(summary = "Get logged in user info")
    @GetMapping("/info")
    public UserResponse getMyInfo() {
        return userService.getMyInfo();
    }

    @Operation(summary = "Get my groups")
    @GetMapping("/groups")
    public List<GroupResponse> getMyGroups() {
        return groupService.getMyGroups();
    }

    @Operation(summary = "Change my password (signs out my other sessions)")
    @PutMapping("/password")
    public ResponseEntity<Void> changePassword(@RequestBody @Validated ChangePasswordRequest request) {
        authenticationService.changePassword(request);
        return ResponseEntity.noContent().build();
    }
}
