package com.rebalance.dto.response;

public record UserWithTokenResponse(Long id, String nickname, String email, String currency, Long personalGroupId,
                                    String token) {
}
