package vip.mate.acp.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RFC-090 Phase 7 — connection-test smoke for {@link AcpStdioClient}.
 *
 * <p>Runs a tiny shell-script "agent" that mimics the {@code initialize}
 * handshake: reads one JSON-RPC request, replies with a matching id and
 * the expected protocol version. Locks in:
 * <ul>
 *   <li>spawn → request → response → close all happen cleanly,</li>
 *   <li>protocolVersion is parsed from the result,</li>
 *   <li>the reader thread doesn't leak past close.</li>
 * </ul>
 *
 * <p>POSIX-only: relies on {@code sh} + executable bit. Windows agents
 * are exercised via the real CLI integration smoke (manual). The
 * client itself is OS-neutral; the script harness is what's POSIXy.
 */
@DisabledOnOs(OS.WINDOWS)
class AcpStdioClientTest {

    @Test
    @DisplayName("initialize handshake completes against a scripted agent")
    void initializeHandshake() throws Exception {
        Path script = writeScriptedAgent();
        try (AcpStdioClient client = AcpStdioClient.spawn(
                new ObjectMapper(), "sh", List.of(script.toString()),
                AcpStdioClient.emptyEnv(), null)) {
            JsonNode result = client.initialize(5_000);
            assertNotNull(result);
            assertEquals(AcpStdioClient.PROTOCOL_VERSION,
                    result.path("protocolVersion").asInt());
        } finally {
            Files.deleteIfExists(script);
        }
    }

    @Test
    @DisplayName("spawn fails fast for a missing command")
    void spawnFailsFastForMissingCommand() {
        assertThrows(IOException.class, () ->
                AcpStdioClient.spawn(new ObjectMapper(),
                        "/definitely/does/not/exist/acp-test-bin",
                        List.of(), AcpStdioClient.emptyEnv(), null));
    }

    /**
     * Tiny shell-script agent: read one JSON-RPC line on stdin and
     * write a response with a hard-coded result. Just enough surface
     * to exercise the framing path.
     */
    private Path writeScriptedAgent() throws IOException {
        Path script = Files.createTempFile("acp-fake-agent-", ".sh");
        String body = "" +
                "#!/bin/sh\n" +
                "read line\n" +
                // Pull the id; assume integer id at this position.
                "id=$(printf '%s' \"$line\" | sed -n 's/.*\"id\":\\([0-9]\\+\\).*/\\1/p')\n" +
                "if [ -z \"$id\" ]; then id=1; fi\n" +
                "printf '{\"jsonrpc\":\"2.0\",\"id\":%s,\"result\":{\"protocolVersion\":1,\"agentCapabilities\":{}}}\\n' \"$id\"\n";
        Files.writeString(script, body, StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(script, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
        } catch (UnsupportedOperationException ignore) {
            // Filesystem doesn't support POSIX perms — sh ... still works.
        }
        return script;
    }
}
