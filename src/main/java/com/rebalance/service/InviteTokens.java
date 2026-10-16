package com.rebalance.service;

import java.security.SecureRandom;
import java.util.Base64;

final class InviteTokens {
    private static final SecureRandom RANDOM = new SecureRandom();

    private InviteTokens() {
    }

    /** 128 random bits, URL-safe (22 characters). */
    static String generate() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
