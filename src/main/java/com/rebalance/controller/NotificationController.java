package com.rebalance.controller;

import com.rebalance.dto.request.IdsRequest;
import com.rebalance.dto.response.NotificationResponse;
import com.rebalance.service.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Notification management")
@RequiredArgsConstructor
@RestController
@RequestMapping(APIVersion.current + "/notifications")
public class NotificationController {
    private final NotificationService notificationService;

    @Operation(summary = "Get my unseen notifications, newest first")
    @GetMapping("/unseen")
    public List<NotificationResponse> getUnseen() {
        return notificationService.getUnseen();
    }

    @Operation(summary = "Mark notifications as seen")
    @PostMapping("/seen")
    public ResponseEntity<Void> markSeen(@RequestBody @Validated IdsRequest request) {
        notificationService.markSeen(request.ids());
        return ResponseEntity.noContent().build();
    }
}
