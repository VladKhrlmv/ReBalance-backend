package com.rebalance.security;

import com.rebalance.repository.AuthTokenRepository;
import com.rebalance.util.Time;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class TokenCleanupJob {
    private final AuthTokenRepository authTokenRepository;

    @Scheduled(cron = "0 17 * * * *")
    @Transactional
    public void deleteExpiredTokens() {
        int deleted = authTokenRepository.deleteExpired(Time.nowUtc());
        if (deleted > 0) {
            log.info("Deleted {} expired auth tokens", deleted);
        }
    }
}
