package com.rebalance.dto.response;

public record InvitePreviewResponse(Long groupId, String name, String currency, long memberCount, boolean alreadyMember) {
}
