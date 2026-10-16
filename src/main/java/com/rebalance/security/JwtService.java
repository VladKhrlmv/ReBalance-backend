package com.rebalance.security;

import com.rebalance.config.AppProperties;
import com.rebalance.entity.AuthToken;
import com.rebalance.entity.User;
import com.rebalance.repository.AuthTokenRepository;
import com.rebalance.repository.UserRepository;
import com.rebalance.util.Time;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * Issues and validates JWTs. Every issued token is also stored (as a SHA-256 hash) so it can be revoked;
 * a token is accepted only if its signature and expiry are valid AND it hasn't been revoked.
 */
@Service
public class JwtService {
    private static final int MIN_KEY_BYTES = 32;

    private final SecretKey key;
    private final Duration ttl;
    private final AuthTokenRepository authTokenRepository;
    private final UserRepository userRepository;

    public JwtService(AppProperties properties, AuthTokenRepository authTokenRepository, UserRepository userRepository) {
        String secret = properties.jwt() == null ? null : properties.jwt().secret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("JWT_SECRET (app.jwt.secret) is not set. Generate one with: openssl rand -base64 48");
        }
        byte[] keyBytes = Decoders.BASE64.decode(secret.trim());
        if (keyBytes.length < MIN_KEY_BYTES) {
            throw new IllegalStateException("JWT_SECRET must decode to at least " + MIN_KEY_BYTES + " bytes");
        }
        this.key = Keys.hmacShaKeyFor(keyBytes);
        this.ttl = properties.jwt().ttl();
        this.authTokenRepository = authTokenRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public String issue(User user) {
        LocalDateTime now = Time.nowUtc();
        LocalDateTime expiresAt = now.plus(ttl);
        String token = Jwts.builder()
                .subject(String.valueOf(user.getId()))
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(Time.toInstant(now)))
                .expiration(Date.from(Time.toInstant(expiresAt)))
                .signWith(key)
                .compact();

        AuthToken authToken = new AuthToken();
        authToken.setUserId(user.getId());
        authToken.setTokenHash(hash(token));
        authToken.setCreatedAt(now);
        authToken.setExpiresAt(expiresAt);
        authTokenRepository.save(authToken);
        return token;
    }

    /**
     * @return the token's user if the token is valid and not revoked; empty otherwise (never throws for bad tokens)
     */
    @Transactional(readOnly = true)
    public Optional<User> authenticate(String token) {
        Claims claims;
        try {
            claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }

        Long userId;
        try {
            userId = Long.valueOf(claims.getSubject());
        } catch (NumberFormatException e) {
            return Optional.empty();
        }

        LocalDateTime now = Time.nowUtc();
        boolean stored = authTokenRepository.findByTokenHash(hash(token))
                .filter(t -> t.getUserId().equals(userId) && t.getExpiresAt().isAfter(now))
                .isPresent();
        if (!stored) {
            return Optional.empty();
        }
        return userRepository.findById(userId);
    }

    @Transactional
    public void revoke(String token) {
        authTokenRepository.deleteByTokenHash(hash(token));
    }

    @Transactional
    public void revokeAllExcept(Long userId, String keptToken) {
        authTokenRepository.deleteAllByUserIdExcept(userId, hash(keptToken));
    }

    @Transactional
    public void revokeAll(Long userId) {
        authTokenRepository.deleteAllByUserId(userId);
    }

    static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
