package com.rebalance.security;

import com.rebalance.entity.User;
import com.rebalance.exception.RebalanceErrorType;
import com.rebalance.exception.RebalanceException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class SignedInUsernameGetter {
    public User getUser() {
        return findUser().orElseThrow(() -> new RebalanceException(RebalanceErrorType.RB_401));
    }

    public Optional<User> findUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof User user) {
            return Optional.of(user);
        }
        return Optional.empty();
    }

    /** The raw JWT of the current request. */
    public String getToken() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getCredentials() instanceof String token) {
            return token;
        }
        throw new RebalanceException(RebalanceErrorType.RB_401);
    }
}
