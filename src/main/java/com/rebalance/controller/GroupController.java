package com.rebalance.controller;

import com.rebalance.dto.request.FavoriteRequest;
import com.rebalance.dto.request.GroupCreateRequest;
import com.rebalance.dto.request.GroupUpdateRequest;
import com.rebalance.dto.request.InviteSettingsRequest;
import com.rebalance.dto.response.DebtSettlementResponse;
import com.rebalance.dto.response.GroupResponse;
import com.rebalance.dto.response.MemberResponse;
import com.rebalance.service.GroupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Group management")
@RestController
@RequestMapping(APIVersion.current + "/group")
@RequiredArgsConstructor
public class GroupController {
    private final GroupService groupService;

    @Operation(summary = "Create new group")
    @PostMapping
    public ResponseEntity<GroupResponse> createGroup(@RequestBody @Validated GroupCreateRequest request) {
        return new ResponseEntity<>(groupService.createGroup(request), HttpStatus.CREATED);
    }

    @Operation(summary = "Get group information")
    @GetMapping("/{groupId}")
    public GroupResponse getGroup(@PathVariable Long groupId) {
        return groupService.getGroup(groupId);
    }

    @Operation(summary = "Rename group")
    @PatchMapping("/{groupId}")
    public GroupResponse renameGroup(@PathVariable Long groupId, @RequestBody @Validated GroupUpdateRequest request) {
        return groupService.renameGroup(groupId, request.name());
    }

    @Operation(summary = "Delete group (owner only)")
    @DeleteMapping("/{groupId}")
    public ResponseEntity<Void> deleteGroup(@PathVariable Long groupId) {
        groupService.deleteGroup(groupId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Mark group as favorite or not")
    @PutMapping("/{groupId}/favorite")
    public ResponseEntity<Void> setFavorite(@PathVariable Long groupId, @RequestBody @Validated FavoriteRequest request) {
        groupService.setFavorite(groupId, request.favorite());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Get members of group with their balances")
    @GetMapping("/{groupId}/members")
    public List<MemberResponse> getMembers(@PathVariable Long groupId) {
        return groupService.getMembers(groupId);
    }

    @Operation(summary = "Leave group (balance must be zero)")
    @PostMapping("/{groupId}/leave")
    public ResponseEntity<Void> leaveGroup(@PathVariable Long groupId) {
        groupService.leaveGroup(groupId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Remove member (owner only, member's balance must be zero)")
    @DeleteMapping("/{groupId}/members/{memberId}")
    public ResponseEntity<Void> removeMember(@PathVariable Long groupId, @PathVariable Long memberId) {
        groupService.removeMember(groupId, memberId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Get suggested transfers to settle the group")
    @GetMapping("/{groupId}/debt-settlement")
    public List<DebtSettlementResponse> getDebtSettlement(@PathVariable Long groupId) {
        return groupService.getDebtSettlement(groupId);
    }

    @Operation(summary = "Generate a new invite link (owner only)")
    @PostMapping("/{groupId}/invite/rotate")
    public GroupResponse rotateInvite(@PathVariable Long groupId) {
        return groupService.rotateInvite(groupId);
    }

    @Operation(summary = "Enable or disable the invite link (owner only)")
    @PutMapping("/{groupId}/invite")
    public GroupResponse setInviteEnabled(@PathVariable Long groupId, @RequestBody @Validated InviteSettingsRequest request) {
        return groupService.setInviteEnabled(groupId, request.enabled());
    }
}
