package com.rebalance.admin;

import com.rebalance.entity.User;
import com.rebalance.repository.UserRepository;
import com.rebalance.security.JwtService;
import com.rebalance.service.AuthenticationService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.util.List;
import java.util.Optional;

/**
 * Admin command for resetting a forgotten password (there is no email service):
 * <pre>
 * docker compose run --rm --no-deps backend --spring.main.web-application-type=none --reset-password=user@example.com
 * </pre>
 * Prints a temporary password, signs the user out everywhere and exits.
 */
@Component
@RequiredArgsConstructor
public class PasswordResetRunner implements ApplicationRunner {
    static final String OPTION = "reset-password";
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final TransactionTemplate transactionTemplate;
    private final ConfigurableApplicationContext context;

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption(OPTION)) {
            return;
        }
        List<String> values = args.getOptionValues(OPTION);
        String email = values == null || values.isEmpty() ? "" : AuthenticationService.normalizeEmail(values.get(0));

        Integer exitCode = transactionTemplate.execute(status -> {
            Optional<User> user = email.isEmpty() ? Optional.empty() : userRepository.findByEmail(email);
            if (user.isEmpty()) {
                System.out.println("No user with email '" + email + "'. Usage: --" + OPTION + "=<email>");
                return 1;
            }
            String temporaryPassword = generateTemporaryPassword();
            user.get().setPassword(passwordEncoder.encode(temporaryPassword));
            jwtService.revokeAll(user.get().getId());
            System.out.println("Password of " + email + " was reset. All sessions were signed out.");
            System.out.println("Temporary password: " + temporaryPassword);
            System.out.println("Ask the user to log in and change it (account menu -> Change password).");
            return 0;
        });

        int code = exitCode == null ? 1 : exitCode;
        System.exit(SpringApplication.exit(context, () -> code));
    }

    /** 4 groups of 4 characters plus a digit and a symbol, so it also satisfies the password rules. */
    static String generateTemporaryPassword() {
        StringBuilder password = new StringBuilder();
        for (int group = 0; group < 4; group++) {
            if (group > 0) {
                password.append('-');
            }
            for (int i = 0; i < 4; i++) {
                password.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
            }
        }
        return password.append("7a").toString();
    }
}
