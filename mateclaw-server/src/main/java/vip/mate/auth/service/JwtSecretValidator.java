package vip.mate.auth.service;

import java.nio.charset.StandardCharsets;
import java.util.Set;

public final class JwtSecretValidator {

    private static final int MINIMUM_KEY_LENGTH_BYTES = 32;
    private static final Set<String> BUILT_IN_SECRETS = Set.of(
            "MateClaw-JWT-Secret-Key-2024-Please-Change-In-Production",
            "MateClaw-Secret-Key-2024-Very-Long-String"
    );

    private JwtSecretValidator() {
    }

    public static void validate(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("JWT secret must be configured and non-blank");
        }
        if (BUILT_IN_SECRETS.contains(secret.strip())) {
            throw new IllegalArgumentException("JWT secret must not use a built-in value");
        }
        if (secret.getBytes(StandardCharsets.UTF_8).length < MINIMUM_KEY_LENGTH_BYTES) {
            throw new IllegalArgumentException("JWT secret must contain at least 32 UTF-8 bytes");
        }
    }
}
