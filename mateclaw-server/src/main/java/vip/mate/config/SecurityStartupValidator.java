package vip.mate.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import vip.mate.auth.service.JwtSecretValidator;

/**
 * Startup security validator — rejects insecure JWT configuration and warns about other risks.
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@Order(1)
public class SecurityStartupValidator implements ApplicationRunner {

    @Value("${spring.h2.console.enabled:false}")
    private boolean h2ConsoleEnabled;

    @Value("${mateclaw.cors.allowed-origins:*}")
    private String corsOrigins;

    public SecurityStartupValidator(@Value("${mateclaw.jwt.secret}") String jwtSecret) {
        try {
            JwtSecretValidator.validate(jwtSecret);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Invalid JWT secret configuration: " + e.getMessage(), e);
        }
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean hasWarnings = false;

        if (h2ConsoleEnabled) {
            log.warn("[Security] H2 Console is enabled at /h2-console. Set H2_CONSOLE_ENABLED=false in production.");
            hasWarnings = true;
        }

        if ("*".equals(corsOrigins.trim())) {
            log.warn("[Security] CORS allows all origins. Set MATECLAW_CORS_ALLOWED_ORIGINS in production.");
            hasWarnings = true;
        }

        if (!hasWarnings) {
            log.info("[Security] Startup security check passed.");
        }
    }
}
