package vip.mate.tool.mcp.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives {@link McpClientManager#wrapServerCallbacks(long, ToolCallback[])}
 * directly so the manager's collision-and-skip logic can be exercised
 * without standing up a real MCP client.
 */
class McpClientManagerWrapTest {

    @Test
    @DisplayName("two distinct raw callbacks both wrap and survive")
    void twoDistinctCallbacksSurvive() {
        ToolCallback a = stub("create_issue");
        ToolCallback b = stub("list_issues");

        List<ToolCallback> wrapped = McpClientManager.wrapServerCallbacks(42L,
                new ToolCallback[]{a, b});

        assertEquals(2, wrapped.size());
        assertEquals(McpToolNameResolver.prefixedName(42L, "create_issue"),
                wrapped.get(0).getToolDefinition().name());
        assertEquals(McpToolNameResolver.prefixedName(42L, "list_issues"),
                wrapped.get(1).getToolDefinition().name());
    }

    @Test
    @DisplayName("duplicate raw callback: only the first survives, second is skipped")
    void duplicateRawSecondCallbackSkipped() {
        // The previous Map<raw, decision> shape would have looked up the
        // first (bindable) decision for both callbacks, registering two
        // wrapped callbacks under the same prefixed name. Lockstep
        // alignment prevents that — the second should be dropped before
        // wrapping happens.
        ToolCallback first = stub("search");
        ToolCallback duplicate = stub("search");

        List<ToolCallback> wrapped = McpClientManager.wrapServerCallbacks(42L,
                new ToolCallback[]{first, duplicate});

        assertEquals(1, wrapped.size());
        assertSame(((PrefixedNameToolCallback) wrapped.get(0)).getDelegate(), first);
    }

    @Test
    @DisplayName("hash-colliding raw pair: only the first survives")
    void hashCollisionSecondCallbackSkipped() {
        String[] pair = McpHashCollisionDetectorTest.hashCollidingPair();
        if (pair == null) {
            // The detector test guarantees @BeforeAll populates the pair
            // when this class runs alongside it; if it ran in isolation we
            // recompute defensively. Either way the assertion below holds.
            pair = findPair();
        }
        ToolCallback first = stub(pair[0]);
        ToolCallback collider = stub(pair[1]);

        List<ToolCallback> wrapped = McpClientManager.wrapServerCallbacks(42L,
                new ToolCallback[]{first, collider});

        assertEquals(1, wrapped.size());
        assertSame(((PrefixedNameToolCallback) wrapped.get(0)).getDelegate(), first);
    }

    @Test
    @DisplayName("blank raw is dropped without consuming a decision")
    void blankRawDoesNotMisalignDecisions() {
        ToolCallback good = stub("search");
        // DefaultToolDefinition's builder rejects blank names, so we build
        // a hand-rolled ToolCallback whose ToolDefinition reports an empty
        // string. The defensive blank-name handling in wrapServerCallbacks
        // is exactly what protects against this kind of upstream surprise.
        ToolCallback blank = new BlankNameCallback();
        ToolCallback alsoGood = stub("read_file");

        List<ToolCallback> wrapped = McpClientManager.wrapServerCallbacks(42L,
                new ToolCallback[]{good, blank, alsoGood});

        // Both real callbacks survive; the blank entry is silently dropped
        // and does NOT advance the decision pointer, otherwise alsoGood
        // would have looked up search's bindable decision and wrapped under
        // the wrong name.
        assertEquals(2, wrapped.size());
        List<String> names = wrapped.stream()
                .map(cb -> cb.getToolDefinition().name())
                .collect(Collectors.toList());
        assertTrue(names.contains(McpToolNameResolver.prefixedName(42L, "search")));
        assertTrue(names.contains(McpToolNameResolver.prefixedName(42L, "read_file")));
    }

    /** Callback that surfaces a blank ToolDefinition.name() — exists only so
     *  the test can drive the defensive branch in {@code wrapServerCallbacks}
     *  that the upstream builder otherwise prevents. */
    private static final class BlankNameCallback implements ToolCallback {
        private final ToolDefinition def = new ToolDefinition() {
            @Override public String name() { return ""; }
            @Override public String description() { return ""; }
            @Override public String inputSchema() { return "{}"; }
        };
        @Override public ToolDefinition getToolDefinition() { return def; }
        @Override public ToolMetadata getToolMetadata() { return ToolCallback.super.getToolMetadata(); }
        @Override public String call(String toolInput) { return ""; }
        @Override public String call(String toolInput, ToolContext toolContext) { return ""; }
    }

    @Test
    @DisplayName("empty input returns an empty list")
    void emptyInput() {
        List<ToolCallback> wrapped = McpClientManager.wrapServerCallbacks(42L, new ToolCallback[0]);
        assertEquals(0, wrapped.size());
    }

    private static String[] findPair() {
        String anchor = "xxxxxxxxxxxxxxxxxxxx";
        java.util.Map<String, String> seen = new java.util.HashMap<>();
        for (int i = 0; i < 1_000_000; i++) {
            String raw = anchor + i;
            String hash = McpToolNameResolver.hash6(raw);
            String prior = seen.put(hash, raw);
            if (prior != null) return new String[]{prior, raw};
        }
        throw new IllegalStateException("hash distribution broken");
    }

    private static ToolCallback stub(String name) {
        ToolDefinition def = DefaultToolDefinition.builder()
                .name(name)
                .description("")
                .inputSchema("{}")
                .build();
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() { return def; }
            @Override
            public ToolMetadata getToolMetadata() { return ToolCallback.super.getToolMetadata(); }
            @Override
            public String call(String toolInput) { return name + ":" + toolInput; }
            @Override
            public String call(String toolInput, ToolContext toolContext) { return call(toolInput); }
        };
    }
}
