package vip.mate.kbopen.auth;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Shared token hash kernel (A4).
 *
 * <p>Generates cryptographically random token plaintext, computes its SHA-256
 * hash for storage, and verifies a plaintext against a stored hash. This is
 * the single source of truth for token hashing so that KB API Keys and (in a
 * future refactor) PAT share one implementation instead of drifting apart.
 *
 * <p>Extracted from {@code PersonalAccessTokenService}'s private hash methods
 * — the logic is identical; making it a Spring bean lets both services inject
 * it without duplicating the SHA-256 boilerplate.
 */
@Component
public class TokenHashUtil {

    private final SecureRandom secureRandom = new SecureRandom();

    /** Hash a plaintext token to its lowercase SHA-256 hex digest. */
    public String hash(String plaintext) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(plaintext.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable on this JVM", e);
        }
    }

    /**
     * Generate a random plaintext token of the given byte-entropy, prefixed
     * with {@code prefix} (e.g. {@code "mck_"}).
     */
    public String generate(String prefix, int entropyBytes) {
        byte[] bytes = new byte[entropyBytes];
        secureRandom.nextBytes(bytes);
        String body = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        return prefix + body;
    }

    /** Verify that a plaintext hashes to the expected stored digest. */
    public boolean matches(String plaintext, String storedHash) {
        if (plaintext == null || storedHash == null) {
            return false;
        }
        return hash(plaintext).equals(storedHash);
    }
}
