package vip.mate.llm.anthropic.oauth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the JSON parsing path of {@link ClaudeCodeCredentialsReader}, which
 * is the only path exercised on Linux/Windows servers. Keychain reading is
 * a macOS-only ProcessBuilder integration — left to manual / live testing.
 */
class ClaudeCodeCredentialsReaderTest {

    private ClaudeCodeCredentialsReader reader;

    @BeforeEach
    void setUp() {
        reader = new ClaudeCodeCredentialsReader(new ObjectMapper());
    }

    @Test
    @DisplayName("parseCredentials extracts all fields from the canonical envelope")
    void parseCredentials_fullPayload() {
        String json = """
                {
                  "claudeAiOauth": {
                    "accessToken": "sk-ant-oat01-test",
                    "refreshToken": "sk-ant-ort01-test",
                    "expiresAt": 1735689600000,
                    "scopes": ["user:inference", "user:profile"]
                  }
                }
                """;
        Optional<ClaudeCodeCredentials> result =
                reader.parseCredentials(json, ClaudeCodeCredentials.Source.CREDENTIALS_FILE);
        assertTrue(result.isPresent());
        ClaudeCodeCredentials c = result.get();
        assertEquals("sk-ant-oat01-test", c.accessToken());
        assertEquals("sk-ant-ort01-test", c.refreshToken());
        assertEquals(1735689600000L, c.expiresAtMs());
        assertEquals(ClaudeCodeCredentials.Source.CREDENTIALS_FILE, c.source());
    }

    @Test
    @DisplayName("parseCredentials returns empty when claudeAiOauth missing")
    void parseCredentials_missingEnvelope() {
        // Some users have only {primaryApiKey: "..."} in ~/.claude.json — that's
        // an Anthropic console managed key, not OAuth, so we must NOT pretend
        // it's a Claude Code credential.
        Optional<ClaudeCodeCredentials> result = reader.parseCredentials(
                "{\"primaryApiKey\":\"sk-ant-test\"}",
                ClaudeCodeCredentials.Source.CREDENTIALS_FILE);
        assertFalse(result.isPresent());
    }

    @Test
    @DisplayName("parseCredentials returns empty when accessToken blank")
    void parseCredentials_blankToken() {
        String json = """
                { "claudeAiOauth": { "accessToken": "", "refreshToken": "rt" } }
                """;
        Optional<ClaudeCodeCredentials> result =
                reader.parseCredentials(json, ClaudeCodeCredentials.Source.CREDENTIALS_FILE);
        assertFalse(result.isPresent());
    }

    @Test
    @DisplayName("parseCredentials handles missing refreshToken gracefully")
    void parseCredentials_missingRefreshToken() {
        // Older Claude Code versions wrote the access token without a refresh
        // token. Reader must still surface those — refresh just won't be possible.
        String json = """
                { "claudeAiOauth": { "accessToken": "at-only", "expiresAt": 0 } }
                """;
        Optional<ClaudeCodeCredentials> result =
                reader.parseCredentials(json, ClaudeCodeCredentials.Source.CREDENTIALS_FILE);
        assertTrue(result.isPresent());
        assertEquals("at-only", result.get().accessToken());
        assertFalse(result.get().canRefresh());
    }

    @Test
    @DisplayName("parseCredentials rejects malformed JSON without throwing")
    void parseCredentials_badJson() {
        Optional<ClaudeCodeCredentials> result = reader.parseCredentials(
                "{not json", ClaudeCodeCredentials.Source.CREDENTIALS_FILE);
        assertFalse(result.isPresent());
    }

    @Test
    @DisplayName("parseCredentials returns empty for null/blank input")
    void parseCredentials_blankInput() {
        assertFalse(reader.parseCredentials(null, ClaudeCodeCredentials.Source.CREDENTIALS_FILE).isPresent());
        assertFalse(reader.parseCredentials("", ClaudeCodeCredentials.Source.CREDENTIALS_FILE).isPresent());
        assertFalse(reader.parseCredentials("   ", ClaudeCodeCredentials.Source.CREDENTIALS_FILE).isPresent());
    }

    @Test
    @DisplayName("readFromJsonFile returns empty for missing path")
    void readFromJsonFile_missing(@TempDir Path tmp) {
        Path absent = tmp.resolve("nonexistent.json");
        assertFalse(reader.readFromJsonFile(absent).isPresent());
    }

    @Test
    @DisplayName("readFromJsonFile reads + parses an existing file")
    void readFromJsonFile_present(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve(".credentials.json");
        Files.writeString(file, """
                { "claudeAiOauth": {
                    "accessToken": "from-file",
                    "refreshToken": "rt-from-file",
                    "expiresAt": 0
                } }
                """, StandardCharsets.UTF_8);

        Optional<ClaudeCodeCredentials> result = reader.readFromJsonFile(file);
        assertTrue(result.isPresent());
        assertEquals("from-file", result.get().accessToken());
        assertEquals(ClaudeCodeCredentials.Source.CREDENTIALS_FILE, result.get().source());
    }

    @Test
    @DisplayName("readFromKeychain returns empty on non-macOS hosts")
    void readFromKeychain_nonMacOs() {
        // Override isMacOs() to false so the test passes regardless of CI host.
        ClaudeCodeCredentialsReader linux = new ClaudeCodeCredentialsReader(new ObjectMapper()) {
            @Override
            boolean isMacOs() { return false; }
        };
        assertFalse(linux.readFromKeychain().isPresent());
    }
}
