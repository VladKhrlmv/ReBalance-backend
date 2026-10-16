package com.rebalance.controller;

import com.rebalance.dto.response.GroupResponse;
import com.rebalance.dto.response.InvitePreviewResponse;
import com.rebalance.security.RateLimiter;
import com.rebalance.service.InviteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;

@Tag(name = "Invites")
@RestController
@RequestMapping(APIVersion.current + "/invites")
@RequiredArgsConstructor
public class InviteController {
    private static final int MAX_PREVIEWS_PER_IP = 60;
    private static final Duration PREVIEW_WINDOW = Duration.ofMinutes(1);

    private final InviteService inviteService;
    private final RateLimiter rateLimiter;

    @Operation(summary = "Preview the group behind an invite link (no login needed)")
    @GetMapping("/{token}")
    public InvitePreviewResponse preview(@PathVariable String token, HttpServletRequest request) {
        rateLimiter.hit("invite-ip:" + request.getRemoteAddr(), MAX_PREVIEWS_PER_IP, PREVIEW_WINDOW);
        return inviteService.preview(token);
    }

    @Operation(summary = "Join the group behind an invite link")
    @PostMapping("/{token}/join")
    public GroupResponse join(@PathVariable String token) {
        return inviteService.join(token);
    }
}
