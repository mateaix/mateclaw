package vip.mate.llm.anthropic.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validates the JSON-file write path of {@link ClaudeCodeCredentialsWriter},
 * with focus on the two correctness-critical behaviors:
 *
 * <ol>
 *   <li>Concurrent-write defence: when Claude Code itself rewrites the file
 *       while MateClaw is mid-refresh, the writer must NOT clobber.</li>
 *   <li>Scope preservation: the writer must keep the {@code scopes} array
 *       (Claude Code &gt;= 2.1.81 needs {@code user:inference} or it shows
 *       the user as logged-out).</li>
 * </ol>
 */
class ClaudeCodeCredentialsWriterTest {

    private ObjectMapper mapper;
    private ClaudeCodeCredentialsWriter writer;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        writer = new ClaudeCodeCredentialsWriter(mapper);
    }

    @Test
    @DisplayName("writeJsonFile creates a new file when none exists")
    void writeJsonFile_createsNew(@TempDir Path tmp) throws IOException {
        Path target = tmp.resolve(".credentials.json");
        ClaudeCodeCredentials fresh = new ClaudeCodeCredentials(
                "new-access", "new-refresh", 9_999_999_999L,
                ClaudeCodeCredentials.Source.CREDENTIALS_FILE);

        boolean ok = writer.writeJsonFile(target, null, fresh);
        assertTrue(ok);
        assertTrue(Files.exists(target));

        JsonNode root = mapper.readTree(Files.readString(target, StandardCharsets.UTF_8));
        JsonNode oauth = root.path("claudeAiOauth");
        assertEquals("new-access", oauth.path("accessToken").asText());
        assertEquals("new-refresh", oauth.path("refreshToken").asText());
        assertEquals(9_999_999_999L, oauth.path("expiresAt").asLong());
        // Default scope must be present so Claude Code 2.1.81+ keeps recognising
        // the credential after MateClaw writes to it.
        assertTrue(oauth.path("scopes").isArray());
        assertEquals("user:inference", oauth.path("scopes").get(0).asText());
    }

    @Test
    @DisplayName("writeJsonFile preserves existing scopes")
    void writeJsonFile_preservesScopes(@TempDir Path tmp) throws IOException {
        Path target = tmp.resolve(".credentials.json");
        Files.writeString(target, """
                { "claudeAiOauth": {
                    "accessToken": "old-token",
                    "refreshToken": "old-refresh",
                    "expiresAt": 1,
                    "scopes": ["user:inference", "user:profile", "extra:scope"]
                } }
                """, StandardCharsets.UTF_8);

        ClaudeCodeCredentials fresh = new ClaudeCodeCredentials(
                "new-access", "new-refresh", 9_999_999_999L,
                ClaudeCodeCredentials.Source.CREDENTIALS_FILE);
        boolean ok = writer.writeJsonFile(target, "old-token", fresh);
        assertTrue(ok);

        JsonNode oauth = mapper.readTree(Files.readString(target, StandardCharsets.UTF_8))
                .path("claudeAiOauth");
        assertEquals("new-access", oauth.path("accessToken").asText());
        // All three original scopes survive — the writer mutates only the
        // fields it owns (access/refresh/expiresAt).
        assertEquals(3, oauth.path("scopes").size());
        assertEquals("user:profile", oauth.path("scopes").get(1).asText());
        assertEquals("extra:scope", oauth.path("scopes").get(2).asText());
    }

    @Test
    @DisplayName("writeJsonFile preserves unknown top-level fields")
    void writeJsonFile_preservesUnknownFields(@TempDir Path tmp) throws IOException {
        // Defends against future Claude Code releases that add new fields:
        // we must not strip them on rewrite.
        Path target = tmp.resolve(".credentials.json");
        Files.writeString(target, """
                {
                  "claudeAiOauth": { "accessToken": "x", "expiresAt": 1 },
                  "futureField": { "foo": "bar" }
                }
                """, StandardCharsets.UTF_8);

        ClaudeCodeCredentials fresh = new ClaudeCodeCredentials(
                "new-access", "new-refresh", 100L,
                ClaudeCodeCredentials.Source.CREDENTIALS_FILE);
        writer.writeJsonFile(target, "x", fresh);

        JsonNode root = mapper.readTree(Files.readString(target, StandardCharsets.UTF_8));
        assertEquals("bar", root.path("futureField").path("foo").asText());
    }

    @Test
    @DisplayName("writeJsonFile bails out when on-disk token already changed")
    void writeJsonFile_concurrentWriteDetected(@TempDir Path tmp) throws IOException {
        // Simulate: MateClaw started a refresh from token "T1", Claude Code
        // beat us to it and wrote "T2". MateClaw must NOT overwrite.
        Path target = tmp.resolve(".credentials.json");
        Files.writeString(target, """
                { "claudeAiOauth": {
                    "accessToken": "T2",
                    "refreshToken": "rt2",
                    "expiresAt": 99,
                    "scopes": ["user:inference"]
                } }
                """, StandardCharsets.UTF_8);

        ClaudeCodeCredentials fresh = new ClaudeCodeCredentials(
                "T3", "rt3", 100L,
                ClaudeCodeCredentials.Source.CREDENTIALS_FILE);
        boolean ok = writer.writeJsonFile(target, "T1", fresh);
        assertFalse(ok, "writer must refuse to overwrite a concurrently-updated file");

        // Disk contents unchanged
        JsonNode oauth = mapper.readTree(Files.readString(target, StandardCharsets.UTF_8))
                .path("claudeAiOauth");
        assertEquals("T2", oauth.path("accessToken").asText());
    }

    @Test
    @DisplayName("writeJsonFile proceeds when previousAccessToken is null (first-time write)")
    void writeJsonFile_nullPrevious_proceeds(@TempDir Path tmp) throws IOException {
        Path target = tmp.resolve(".credentials.json");
        Files.writeString(target, """
                { "claudeAiOauth": { "accessToken": "existing", "scopes": ["user:inference"] } }
                """, StandardCharsets.UTF_8);

        ClaudeCodeCredentials fresh = new ClaudeCodeCredentials(
                "fresh-token", "fresh-refresh", 0L,
                ClaudeCodeCredentials.Source.CREDENTIALS_FILE);
        // Null previous → caller doesn't have a baseline (e.g. first import)
        // → skip concurrency check and just write.
        assertTrue(writer.writeJsonFile(target, null, fresh));

        JsonNode oauth = mapper.readTree(Files.readString(target, StandardCharsets.UTF_8))
                .path("claudeAiOauth");
        assertEquals("fresh-token", oauth.path("accessToken").asText());
    }

    @Test
    @DisplayName("write rejects blank access tokens")
    void write_rejectsBlankToken() {
        ClaudeCodeCredentials blank = new ClaudeCodeCredentials(
                "  ", "rt", 0L, ClaudeCodeCredentials.Source.CREDENTIALS_FILE);
        assertFalse(writer.write(null, blank));
    }

    @Test
    @DisplayName("writeKeychain returns false on non-macOS hosts")
    void writeKeychain_nonMacOs() {
        ClaudeCodeCredentialsWriter linux = new ClaudeCodeCredentialsWriter(mapper) {
            @Override
            boolean isMacOs() { return false; }
        };
        ClaudeCodeCredentials creds = new ClaudeCodeCredentials(
                "at", "rt", 0L, ClaudeCodeCredentials.Source.MACOS_KEYCHAIN);
        assertFalse(linux.writeKeychain(null, creds));
    }
}
