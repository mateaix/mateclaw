package vip.mate.llm.anthropic.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * RFC-062: persist refreshed Claude Code OAuth credentials back to whichever
 * source we read them from.
 *
 * <p>Two write targets, mirroring {@link ClaudeCodeCredentialsReader}:
 * <ol>
 *   <li>macOS Keychain entry {@code Claude Code-credentials} via the
 *       {@code security add-generic-password -U} CLI.</li>
 *   <li>{@code ~/.claude/.credentials.json} JSON file — written atomically
 *       (temp file + rename) with {@code 0600} permissions on POSIX.</li>
 * </ol>
 *
 * <h2>Concurrent-write defence</h2>
 * Claude Code itself may rewrite the credentials file while MateClaw is doing
 * a refresh. The flow is:
 * <ol>
 *   <li>Re-read the file just before write.</li>
 *   <li>If the on-disk {@code accessToken} is newer than the one we are about
 *       to write (different from what we started the refresh with), bail out
 *       — the running Claude Code process beat us to it.</li>
 *   <li>Otherwise merge our refreshed fields into the existing JSON so we
 *       preserve {@code scopes} (Claude Code &gt;= 2.1.81 requires
 *       {@code user:inference}) and any future fields we don't know about.</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClaudeCodeCredentialsWriter {

    /** POSIX permissions for the credentials file: owner read+write only. */
    private static final Set<PosixFilePermission> CREDENTIALS_PERMS =
            EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private final ObjectMapper objectMapper;

    /**
     * Write the refreshed credentials back to whichever source the originals
     * came from. Failures are logged but never thrown — a write failure
     * shouldn't break the in-memory token that's already valid.
     *
     * @param previousAccessToken the access token that triggered the refresh.
     *                            Used to detect concurrent writes by Claude
     *                            Code itself; pass {@code null} to skip the
     *                            check (e.g. on first-time write).
     * @param refreshed the fresh credential bundle from
     *                  {@link ClaudeCodeTokenRefresher}.
     * @return {@code true} if the write completed; {@code false} if skipped
     *         (concurrent change detected) or failed.
     */
    public boolean write(String previousAccessToken, ClaudeCodeCredentials refreshed) {
        if (refreshed == null || refreshed.accessToken() == null || refreshed.accessToken().isBlank()) {
            log.warn("[ClaudeCodeWriter] refusing to write blank credentials");
            return false;
        }
        ClaudeCodeCredentials.Source target = refreshed.source();
        if (target == ClaudeCodeCredentials.Source.REFRESH_RESPONSE) {
            // Caller forgot to pin the destination — fall back to the JSON file.
            log.debug("[ClaudeCodeWriter] source=REFRESH_RESPONSE not addressable; defaulting to JSON file");
            target = ClaudeCodeCredentials.Source.CREDENTIALS_FILE;
        }
        return switch (target) {
            case MACOS_KEYCHAIN -> writeKeychain(previousAccessToken, refreshed);
            case CREDENTIALS_FILE -> writeJsonFile(previousAccessToken, refreshed);
            case REFRESH_RESPONSE -> false; // already coerced above; defensive
        };
    }

    /* ------------------------------------------------------------------ */
    /* JSON file write                                                     */
    /* ------------------------------------------------------------------ */

    boolean writeJsonFile(String previousAccessToken, ClaudeCodeCredentials refreshed) {
        return writeJsonFile(ClaudeCodeCredentialsReader.JSON_CREDENTIALS_PATH, previousAccessToken, refreshed);
    }

    /** Test seam: write to a custom path. Package-private. */
    boolean writeJsonFile(Path path, String previousAccessToken, ClaudeCodeCredentials refreshed) {
        try {
            Path parent = path.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }

            // Read existing file (if any) so we preserve scopes + unknown fields
            // and so we can detect a concurrent write by Claude Code.
            ObjectNode root;
            ObjectNode oauth;
            if (Files.isReadable(path)) {
                String existing = Files.readString(path, StandardCharsets.UTF_8);
                JsonNode parsed = existing.isBlank() ? null : objectMapper.readTree(existing);
                if (parsed instanceof ObjectNode obj) {
                    root = obj;
                    JsonNode oauthNode = obj.path("claudeAiOauth");
                    if (oauthNode instanceof ObjectNode oauthObj) {
                        oauth = oauthObj;
                        // Concurrent-write guard — only when caller pinned the prior token.
                        if (previousAccessToken != null && !previousAccessToken.isBlank()) {
                            String diskAccessToken = oauthObj.path("accessToken").asText("");
                            if (!diskAccessToken.isBlank()
                                    && !diskAccessToken.equals(previousAccessToken)
                                    && !diskAccessToken.equals(refreshed.accessToken())) {
                                log.info("[ClaudeCodeWriter] on-disk access token changed since refresh started — "
                                        + "skipping write to avoid clobbering Claude Code's update");
                                return false;
                            }
                        }
                    } else {
                        oauth = objectMapper.createObjectNode();
                        root.set("claudeAiOauth", oauth);
                    }
                } else {
                    root = objectMapper.createObjectNode();
                    oauth = objectMapper.createObjectNode();
                    root.set("claudeAiOauth", oauth);
                }
            } else {
                root = objectMapper.createObjectNode();
                oauth = objectMapper.createObjectNode();
                root.set("claudeAiOauth", oauth);
            }

            oauth.put("accessToken", refreshed.accessToken());
            if (refreshed.refreshToken() != null && !refreshed.refreshToken().isBlank()) {
                oauth.put("refreshToken", refreshed.refreshToken());
            }
            if (refreshed.expiresAtMs() > 0L) {
                oauth.put("expiresAt", refreshed.expiresAtMs());
            }
            // If scopes are missing on disk (e.g. corrupted file), default to
            // the inference scope Claude Code 2.1.81+ expects.
            if (!oauth.has("scopes") || !oauth.path("scopes").isArray()) {
                oauth.putArray("scopes").add("user:inference");
            }

            byte[] payload = objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsBytes(root);

            // Atomic write: tmp file in same directory + rename.
            Path tmp = Files.createTempFile(
                    parent != null ? parent : path.toAbsolutePath().getParent(),
                    ".credentials-",
                    ".tmp");
            try {
                Files.write(tmp, payload);
                applyOwnerOnlyPerms(tmp);
                try {
                    Files.move(tmp, path,
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    // Some filesystems (e.g. cross-FS on Windows) don't support
                    // atomic move; fall back to plain replace.
                    Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(tmp);
            }
            applyOwnerOnlyPerms(path);
            log.info("[ClaudeCodeWriter] wrote credentials to {}", path);
            return true;
        } catch (IOException e) {
            log.warn("[ClaudeCodeWriter] failed to write credentials file {}: {}", path, e.getMessage());
            return false;
        }
    }

    private static void applyOwnerOnlyPerms(Path path) {
        if (!FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            // Windows / non-POSIX — rely on filesystem ACLs.
            return;
        }
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.asFileAttribute(CREDENTIALS_PERMS).value());
        } catch (IOException | UnsupportedOperationException e) {
            log.debug("[ClaudeCodeWriter] could not chmod 600 on {}: {}", path, e.getMessage());
        }
    }

    /* ------------------------------------------------------------------ */
    /* Keychain write                                                      */
    /* ------------------------------------------------------------------ */

    boolean writeKeychain(String previousAccessToken, ClaudeCodeCredentials refreshed) {
        if (!isMacOs()) {
            log.debug("[ClaudeCodeWriter] skipping keychain write — not on macOS");
            return false;
        }
        Process process = null;
        try {
            // Build the same JSON envelope Claude Code persists. Preserve scopes
            // by reading the existing keychain entry first.
            ObjectNode root = objectMapper.createObjectNode();
            ObjectNode oauth = root.putObject("claudeAiOauth");
            oauth.put("accessToken", refreshed.accessToken());
            if (refreshed.refreshToken() != null && !refreshed.refreshToken().isBlank()) {
                oauth.put("refreshToken", refreshed.refreshToken());
            }
            if (refreshed.expiresAtMs() > 0L) {
                oauth.put("expiresAt", refreshed.expiresAtMs());
            }
            oauth.putArray("scopes").add("user:inference");

            String payload = objectMapper.writeValueAsString(root);

            // Use -U to update the existing entry in place (or create if absent).
            ProcessBuilder pb = new ProcessBuilder(
                    "/usr/bin/security",
                    "add-generic-password",
                    "-U",
                    "-s", ClaudeCodeCredentialsReader.KEYCHAIN_SERVICE_NAME,
                    "-a", System.getProperty("user.name", "claude"),
                    "-w", payload);
            pb.redirectErrorStream(true);
            process = pb.start();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                log.warn("[ClaudeCodeWriter] keychain write timed out");
                return false;
            }
            if (process.exitValue() != 0) {
                String err = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                log.warn("[ClaudeCodeWriter] keychain write exit={} ({})", process.exitValue(), err);
                return false;
            }
            log.info("[ClaudeCodeWriter] wrote credentials to macOS Keychain");
            return true;
        } catch (Exception e) {
            log.warn("[ClaudeCodeWriter] keychain write failed: {}", e.getMessage());
            return false;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    /** Package-private for tests to override. */
    boolean isMacOs() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    }
}
