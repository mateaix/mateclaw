package vip.mate.llm.anthropic.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * RFC-062: read Claude Code OAuth credentials from local storage.
 *
 * <p>Two sources, in priority order:
 * <ol>
 *   <li><b>macOS Keychain</b> entry {@code Claude Code-credentials} (Claude Code
 *       &gt;= 2.1.114 stores here). Read via the {@code security} CLI tool.</li>
 *   <li><b>JSON file</b> at {@code ~/.claude/.credentials.json} (legacy + Linux/Win).</li>
 * </ol>
 *
 * <p>Both sources contain the same JSON shape:
 * <pre>
 * {
 *   "claudeAiOauth": {
 *     "accessToken": "...",
 *     "refreshToken": "...",
 *     "expiresAt": 1234567890123,
 *     "scopes": ["user:inference", ...]
 *   }
 * }
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClaudeCodeCredentialsReader {

    /** macOS Keychain service name written by Claude Code. */
    static final String KEYCHAIN_SERVICE_NAME = "Claude Code-credentials";

    /** {@code ~/.claude.json primaryApiKey} is a managed key, not OAuth —
     *  intentionally not read here. */
    static final Path JSON_CREDENTIALS_PATH =
            Paths.get(System.getProperty("user.home"), ".claude", ".credentials.json");

    private final ObjectMapper objectMapper;

    /**
     * Read whichever source exists, preferring Keychain on macOS.
     * @return Optional credentials, never throws on the happy "not found" path.
     */
    public Optional<ClaudeCodeCredentials> read() {
        // macOS Keychain has priority on Darwin (and is the only valid source for
        // Claude Code >= 2.1.114 — they migrated off the JSON file)
        if (isMacOs()) {
            Optional<ClaudeCodeCredentials> kc = readFromKeychain();
            if (kc.isPresent()) return kc;
        }
        return readFromJsonFile();
    }

    /**
     * Specifically read from the macOS Keychain. Used for diagnostics; production
     * code should use {@link #read()} which dispatches.
     * @return empty when not on macOS, when {@code security} command isn't
     *         available, or when no entry exists.
     */
    public Optional<ClaudeCodeCredentials> readFromKeychain() {
        if (!isMacOs()) return Optional.empty();

        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "/usr/bin/security",
                    "find-generic-password",
                    "-s", KEYCHAIN_SERVICE_NAME,
                    "-w");  // -w prints just the password (no metadata)
            pb.redirectErrorStream(false);
            process = pb.start();

            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                log.debug("[ClaudeCodeReader] keychain read timed out");
                return Optional.empty();
            }
            if (process.exitValue() != 0) {
                // Exit 44 = "item not found"; anything else also means "no creds for us"
                log.debug("[ClaudeCodeReader] keychain returned exit {}", process.exitValue());
                return Optional.empty();
            }
            String raw = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return parseCredentials(raw, ClaudeCodeCredentials.Source.MACOS_KEYCHAIN);
        } catch (Exception e) {
            log.debug("[ClaudeCodeReader] keychain read failed: {}", e.getMessage());
            return Optional.empty();
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    /**
     * Specifically read from {@code ~/.claude/.credentials.json}.
     * @return empty when the file is absent / unreadable / malformed.
     */
    public Optional<ClaudeCodeCredentials> readFromJsonFile() {
        return readFromJsonFile(JSON_CREDENTIALS_PATH);
    }

    /** Test seam: read from a custom path. Package-private. */
    Optional<ClaudeCodeCredentials> readFromJsonFile(Path path) {
        if (path == null || !Files.isReadable(path)) {
            return Optional.empty();
        }
        try {
            String raw = Files.readString(path, StandardCharsets.UTF_8);
            return parseCredentials(raw, ClaudeCodeCredentials.Source.CREDENTIALS_FILE);
        } catch (IOException e) {
            log.debug("[ClaudeCodeReader] credentials file read failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Parse the canonical Claude Code JSON envelope.
     * Package-private for unit testing.
     */
    Optional<ClaudeCodeCredentials> parseCredentials(String raw, ClaudeCodeCredentials.Source source) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode root = objectMapper.readTree(raw);
            JsonNode oauth = root.path("claudeAiOauth");
            if (oauth.isMissingNode() || !oauth.isObject()) {
                log.debug("[ClaudeCodeReader] payload missing claudeAiOauth object (source={})", source);
                return Optional.empty();
            }
            String accessToken = oauth.path("accessToken").asText("");
            if (accessToken.isBlank()) {
                log.debug("[ClaudeCodeReader] claudeAiOauth.accessToken blank (source={})", source);
                return Optional.empty();
            }
            String refreshToken = oauth.path("refreshToken").asText("");
            long expiresAt = oauth.path("expiresAt").asLong(0L);
            return Optional.of(new ClaudeCodeCredentials(
                    accessToken,
                    refreshToken.isBlank() ? null : refreshToken,
                    expiresAt,
                    source));
        } catch (Exception e) {
            log.debug("[ClaudeCodeReader] JSON parse failed (source={}): {}", source, e.getMessage());
            return Optional.empty();
        }
    }

    /** Package-private for tests to override. */
    boolean isMacOs() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    }
}
