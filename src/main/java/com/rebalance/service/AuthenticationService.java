package com.rebalance.service;

import com.rebalance.dto.request.ChangePasswordRequest;
import com.rebalance.dto.request.LoginRequest;
import com.rebalance.dto.request.RegisterRequest;
import com.rebalance.dto.response.TokenResponse;
import com.rebalance.dto.response.UserWithTokenResponse;
import com.rebalance.entity.Group;
import com.rebalance.entity.User;
import com.rebalance.entity.UserRole;
import com.rebalance.exception.RebalanceErrorType;
import com.rebalance.exception.RebalanceException;
import com.rebalance.repository.UserRepository;
import com.rebalance.security.JwtService;
import com.rebalance.security.RateLimiter;
import com.rebalance.security.SignedInUsernameGetter;
import com.rebalance.util.Time;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AuthenticationService {
    static final int MAX_FAILED_LOGINS = 5;
    static final Duration FAILED_LOGIN_WINDOW = Duration.ofMinutes(15);
    // per-IP limits are generous because testers may share one network (office, home Wi-Fi)
    static final int MAX_LOGINS_PER_IP = 60;
    static final Duration LOGIN_IP_WINDOW = Duration.ofMinutes(10);
    static final int MAX_REGISTRATIONS_PER_IP = 30;
    static final Duration REGISTRATION_WINDOW = Duration.ofHours(1);

    private final UserRepository userRepository;
    private final GroupService groupService;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RateLimiter rateLimiter;
    private final SignedInUsernameGetter signedInUsernameGetter;

    private String dummyPasswordHash;

    @Transactional
    public UserWithTokenResponse register(RegisterRequest request, String clientIp) {
        rateLimiter.hit("register-ip:" + clientIp, MAX_REGISTRATIONS_PER_IP, REGISTRATION_WINDOW);

        String email = normalizeEmail(request.email());
        if (userRepository.existsByEmail(email)) {
            throw new RebalanceException(RebalanceErrorType.RB_001);
        }

        User user = new User();
        user.setEmail(email);
        user.setNickname(request.nickname().trim());
        user.setPassword(passwordEncoder.encode(request.password()));
        user.setRole(UserRole.USER);
        user.setCreatedAt(Time.nowUtc());
        userRepository.save(user);

        Group personalGroup = groupService.createPersonalGroup(user, request.currency());
        String token = jwtService.issue(user);
        return new UserWithTokenResponse(user.getId(), user.getNickname(), user.getEmail(),
                personalGroup.getCurrency(), personalGroup.getId(), token);
    }

    @Transactional
    public TokenResponse login(LoginRequest request, String clientIp) {
        rateLimiter.hit("login-ip:" + clientIp, MAX_LOGINS_PER_IP, LOGIN_IP_WINDOW);

        String email = normalizeEmail(request.email());
        String failuresKey = "login-fail:" + email;
        if (rateLimiter.isLimited(failuresKey, MAX_FAILED_LOGINS, FAILED_LOGIN_WINDOW)) {
            throw new RebalanceException(RebalanceErrorType.RB_004);
        }

        Optional<User> user = userRepository.findByEmail(email);
        // compare against a dummy hash for unknown emails, so response times don't reveal which emails exist
        String hash = user.map(User::getPassword).orElseGet(this::dummyPasswordHash);
        boolean matches = passwordEncoder.matches(request.password(), hash);
        if (user.isEmpty() || !matches) {
            rateLimiter.record(failuresKey);
            throw new RebalanceException(RebalanceErrorType.RB_402);
        }

        rateLimiter.reset(failuresKey);
        return new TokenResponse(jwtService.issue(user.get()));
    }

    @Transactional
    public void logout() {
        jwtService.revoke(signedInUsernameGetter.getToken());
    }

    /** Changes the password and signs out every other session of the user. */
    @Transactional
    public void changePassword(ChangePasswordRequest request) {
        User signedIn = signedInUsernameGetter.getUser();
        String failuresKey = "password-fail:" + signedIn.getId();
        if (rateLimiter.isLimited(failuresKey, MAX_FAILED_LOGINS, FAILED_LOGIN_WINDOW)) {
            throw new RebalanceException(RebalanceErrorType.RB_004);
        }

        User user = userRepository.findById(signedIn.getId())
                .orElseThrow(() -> new RebalanceException(RebalanceErrorType.RB_401));
        if (!passwordEncoder.matches(request.currentPassword(), user.getPassword())) {
            rateLimiter.record(failuresKey);
            throw new RebalanceException(RebalanceErrorType.RB_003);
        }
        rateLimiter.reset(failuresKey);

        user.setPassword(passwordEncoder.encode(request.newPassword()));
        jwtService.revokeAllExcept(user.getId(), signedInUsernameGetter.getToken());
    }

    public static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private synchronized String dummyPasswordHash() {
        if (dummyPasswordHash == null) {
            dummyPasswordHash = passwordEncoder.encode("dummy-password-for-timing");
        }
        return dummyPasswordHash;
    }
}
