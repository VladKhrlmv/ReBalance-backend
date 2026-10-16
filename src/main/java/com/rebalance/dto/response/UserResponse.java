package com.rebalance.dto.response;

public record UserResponse(Long id, String nickname, String email, String currency, Long personalGroupId) {
}
