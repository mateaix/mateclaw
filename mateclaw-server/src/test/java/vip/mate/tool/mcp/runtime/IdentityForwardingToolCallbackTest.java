package vip.mate.tool.mcp.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

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
    @DisplayName("overwrites a NON-STRING LLM value of the reserved key (number / object / array)")
    void overwritesNonStringLlmValue() throws Exception {
        // The model may try to spoof identity with a non-scalar value; node.put
        // replaces any prior value type with the trusted string unconditionally.
        for (String adversarial : new String[]{
                "{\"" + KEY + "\":123}",
                "{\"" + KEY + "\":{\"admin\":true}}",
                "{\"" + KEY + "\":[\"a\",\"b\"]}",
                "{\"" + KEY + "\":null}"}) {
            String out = IdentityForwardingToolCallback.withClaim(adversarial, KEY, "alice");
            JsonNode node = MAPPER.readTree(out);
            assertThat(node.get(KEY).isTextual())
                    .as("reserved key must be coerced to the trusted string for input: %s", adversarial)
                    .isTrue();
            assertThat(node.get(KEY).asText()).isEqualTo("alice");
        }
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
    @DisplayName("sanitize strips CR/LF and caps length (log-injection hardening)")
    void sanitizePreventsLogInjection() {
        // A crafted toolInput surfaces inside Jackson exception messages; the
        // sanitize helper must neutralize newlines (no forged log lines) and
        // cap length so a huge payload can't blow up log volume.
        String crafted = "line1\nline2\r\n2026-07-18 ERROR fake-injected-line\ttab";
        String out = IdentityForwardingToolCallback.sanitize(crafted);
        assertThat(out).as("no CR/LF/control chars survive").doesNotContain("\n", "\r", "\t");
        assertThat(out).contains("line1").contains("line2");   // content preserved
        // Length cap.
        String huge = "x".repeat(500);
        assertThat(IdentityForwardingToolCallback.sanitize(huge).length())
                .as("capped to ~200 chars").isLessThanOrEqualTo(201);
        assertThat(IdentityForwardingToolCallback.sanitize(null)).isEmpty();
    }

    @Test
    @DisplayName("opt-in matching: by id or name, empty set never forwards")
    void optInMatching() {
        McpIdentityForwardProperties p = new McpIdentityForwardProperties();
        assertThat(p.forwardsTo(42L, "svc")).isFalse();           // empty set
        p.setServers(Set.of("svc"));
        assertThat(p.forwardsTo(42L, "svc")).isTrue();            // by name
        assertThat(p.forwardsTo(42L, "other")).isFalse();
        p.setServers(Set.of("42"));
        assertThat(p.forwardsTo(42L, "other")).isTrue();          // by id
    }

    @Test
    @DisplayName("opt-in matching: OR semantics — either id OR name match forwards")
    void forwardsToOrSemantics() {
        // A server listed under BOTH its name and id must forward; a regression
        // that required BOTH to match would silently disable opt-in here.
        McpIdentityForwardProperties p = new McpIdentityForwardProperties();
        p.setServers(Set.of("svc", "42"));
        assertThat(p.forwardsTo(42L, "svc")).isTrue();            // both present
        assertThat(p.forwardsTo(42L, "other")).isTrue();          // id matches, name doesn't
        assertThat(p.forwardsTo(7L, "svc")).isTrue();             // name matches, id doesn't
        assertThat(p.forwardsTo(7L, "other")).isFalse();          // neither
    }

    @Test
    @DisplayName("call(toolInput) with NO ToolContext injects nothing (fail-closed)")
    void callWithoutToolContextInjectsNothing() throws Exception {
        // The single-arg call() has no ToolContext → classify() returns NONE →
        // the input must pass through to the delegate UNMODIFIED. Guards against
        // a future refactor that falls back to a stale ThreadLocal username.
        java.util.concurrent.atomic.AtomicReference<String> captured = new java.util.concurrent.atomic.AtomicReference<>();
        org.springframework.ai.tool.ToolCallback delegate = new org.springframework.ai.tool.ToolCallback() {
            private final org.springframework.ai.tool.definition.ToolDefinition def =
                    org.springframework.ai.tool.definition.DefaultToolDefinition.builder()
                            .name("t").description("").inputSchema("{}").build();
            @Override public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() { return def; }
            @Override public String call(String toolInput) { captured.set(toolInput); return "ok"; }
        };
        McpIdentityForwardProperties props = new McpIdentityForwardProperties();
        props.setServers(Set.of("svc"));
        McpIdentityForwardService idSvc = new McpIdentityForwardService(props);
        IdentityForwardingToolCallback cb = new IdentityForwardingToolCallback(delegate, idSvc, "svc");

        cb.call("{\"q\":\"hi\"}");

        JsonNode node = MAPPER.readTree(captured.get());
        assertThat(node.has(McpIdentityForwardProperties.USER_ARG))
                .as("no ToolContext ⇒ no identity injected").isFalse();
        assertThat(node.has(McpIdentityForwardProperties.TOKEN_ARG)).isFalse();
        assertThat(node.get("q").asText()).isEqualTo("hi");
    }

    @Test
    @DisplayName("forwardsTo: null id and null name never forward (no NPE)")
    void forwardsToNullSafe() {
        McpIdentityForwardProperties p = new McpIdentityForwardProperties();
        p.setServers(Set.of("svc"));
        assertThat(p.forwardsTo(null, null)).isFalse();
        assertThat(p.forwardsTo(null, "svc")).isTrue();
        p.setServers(Set.of("42"));
        assertThat(p.forwardsTo(42L, null)).isTrue();
    }

    @Test
    @DisplayName("audienceFor(null, null) is rejected — no colliding \"null\" audience")
    void audienceForRejectsBothNull() {
        // Two distinct unidentified servers must NOT share the literal "null"
        // audience — that would defeat per-server audience isolation. Require an
        // id or name instead of silently returning a colliding sentinel.
        McpIdentityForwardProperties p = new McpIdentityForwardProperties();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> p.audienceFor(null, null))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> p.audienceFor(null, "  "))
                .isInstanceOf(IllegalArgumentException.class);
        // But a single null is fine — the other identifies the server.
        assertThat(p.audienceFor(null, "svc")).isEqualTo("svc");
        assertThat(p.audienceFor(42L, null)).isEqualTo("42");
    }
}
