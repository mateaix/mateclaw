package vip.mate.tool.mcp.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link IdentityForwardingToolCallback#withClaim} — the in-band
 * JSON merge that carries identity to a STDIO MCP server. Pure string/JSON
 * behavior; identity resolution (plaintext vs token) is tested in
 * {@link McpIdentityForwardServiceTest}.
 */
class IdentityForwardingToolCallbackTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String KEY = McpIdentityForwardProperties.USER_ARG;

    @Test
    @DisplayName("merges the claim into JSON args under the given key")
    void mergesClaim() throws Exception {
        JsonNode n = MAPPER.readTree(IdentityForwardingToolCallback.withClaim("{\"q\":\"hi\"}", KEY, "alice"));
        assertThat(n.get("q").asText()).isEqualTo("hi");
        assertThat(n.get(KEY).asText()).isEqualTo("alice");
    }

    @Test
    @DisplayName("overwrites an LLM-supplied value of the reserved key (no spoofing)")
    void overwritesLlmSuppliedValue() throws Exception {
        String out = IdentityForwardingToolCallback.withClaim(
                "{\"q\":\"hi\",\"" + KEY + "\":\"attacker\"}", KEY, "alice");
        assertThat(MAPPER.readTree(out).get(KEY).asText()).isEqualTo("alice");
    }

    @Test
    @DisplayName("blank/empty input becomes a fresh object carrying the claim")
    void emptyInputGetsObject() throws Exception {
        for (String in : new String[]{null, "", "   "}) {
            String out = IdentityForwardingToolCallback.withClaim(in, KEY, "bob");
            assertThat(MAPPER.readTree(out).get(KEY).asText()).isEqualTo("bob");
        }
    }

    @Test
    @DisplayName("non-object args (array/scalar) are forwarded unchanged, not corrupted")
    void nonObjectInputUnchanged() {
        assertThat(IdentityForwardingToolCallback.withClaim("[1,2,3]", KEY, "alice")).isEqualTo("[1,2,3]");
        assertThat(IdentityForwardingToolCallback.withClaim("\"plain\"", KEY, "alice")).isEqualTo("\"plain\"");
    }

    @Test
    @DisplayName("malformed JSON is forwarded unchanged (surfaces downstream, not masked)")
    void malformedJsonUnchanged() {
        assertThat(IdentityForwardingToolCallback.withClaim("{not json", KEY, "alice")).isEqualTo("{not json");
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
