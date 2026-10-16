package com.rebalance.repository;

import com.rebalance.entity.AuthToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface AuthTokenRepository extends JpaRepository<AuthToken, Long> {
    Optional<AuthToken> findByTokenHash(String tokenHash);

    @Modifying
    @Query("DELETE FROM AuthToken t WHERE t.tokenHash = :tokenHash")
    int deleteByTokenHash(String tokenHash);

    @Modifying
    @Query("DELETE FROM AuthToken t WHERE t.userId = :userId")
    int deleteAllByUserId(Long userId);

    @Modifying
    @Query("DELETE FROM AuthToken t WHERE t.userId = :userId AND t.tokenHash <> :keepTokenHash")
    int deleteAllByUserIdExcept(Long userId, String keepTokenHash);

    @Modifying
    @Query("DELETE FROM AuthToken t WHERE t.expiresAt < :now")
    int deleteExpired(LocalDateTime now);
}
