package vip.mate.tool.mcp.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.tool.builtin.ToolExecutionContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link IdentityForwardingToolCallback#withIdentity} — the
 * in-band identity injection that lets a STDIO MCP server forward the caller to
 * its REST backend. Pure string/JSON behavior, runs everywhere.
 */
class IdentityForwardingToolCallbackTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String KEY = McpIdentityForwardProperties.IDENTITY_ARG;

    @AfterEach
    void clearContext() {
        ToolExecutionContext.clear();
    }

    @Test
    @DisplayName("injects username into JSON args under the reserved key")
    void injectsUsername() throws Exception {
        String out = IdentityForwardingToolCallback.withIdentity("{\"q\":\"hi\"}", "alice");
        JsonNode n = MAPPER.readTree(out);
        assertThat(n.get("q").asText()).isEqualTo("hi");
        assertThat(n.get(KEY).asText()).isEqualTo("alice");
    }

    @Test
    @DisplayName("overwrites an LLM-supplied value of the reserved key (no spoofing)")
    void overwritesLlmSuppliedIdentity() throws Exception {
        String out = IdentityForwardingToolCallback.withIdentity(
                "{\"q\":\"hi\",\"" + KEY + "\":\"attacker\"}", "alice");
        assertThat(MAPPER.readTree(out).get(KEY).asText()).isEqualTo("alice");
    }

    @Test
    @DisplayName("blank/empty input becomes a fresh object carrying the identity")
    void emptyInputGetsObject() throws Exception {
        for (String in : new String[]{null, "", "   "}) {
            String out = IdentityForwardingToolCallback.withIdentity(in, "bob");
            assertThat(MAPPER.readTree(out).get(KEY).asText()).isEqualTo("bob");
        }
    }

    @Test
    @DisplayName("no authenticated user → input forwarded unchanged (never fabricate identity)")
    void noUserLeavesInputUnchanged() {
        assertThat(IdentityForwardingToolCallback.withIdentity("{\"q\":\"hi\"}", null)).isEqualTo("{\"q\":\"hi\"}");
        assertThat(IdentityForwardingToolCallback.withIdentity("{\"q\":\"hi\"}", "  ")).isEqualTo("{\"q\":\"hi\"}");
    }

    @Test
    @DisplayName("non-object args (array/scalar) are forwarded unchanged, not corrupted")
    void nonObjectInputUnchanged() {
        assertThat(IdentityForwardingToolCallback.withIdentity("[1,2,3]", "alice")).isEqualTo("[1,2,3]");
        assertThat(IdentityForwardingToolCallback.withIdentity("\"plain\"", "alice")).isEqualTo("\"plain\"");
    }

    @Test
    @DisplayName("malformed JSON is forwarded unchanged (surfaces downstream, not masked)")
    void malformedJsonUnchanged() {
        assertThat(IdentityForwardingToolCallback.withIdentity("{not json", "alice")).isEqualTo("{not json");
    }

    @Test
    @DisplayName("opt-in matching: by id or name, empty set never forwards")
    void optInMatching() {
        McpIdentityForwardProperties p = new McpIdentityForwardProperties();
        assertThat(p.forwardsTo(42L, "svc")).isFalse();           // empty set
        p.setServers(java.util.Set.of("svc"));
        assertThat(p.forwardsTo(42L, "svc")).isTrue();            // by name
        assertThat(p.forwardsTo(42L, "other")).isFalse();
        p.setServers(java.util.Set.of("42"));
        assertThat(p.forwardsTo(42L, "other")).isTrue();          // by id
    }
}
