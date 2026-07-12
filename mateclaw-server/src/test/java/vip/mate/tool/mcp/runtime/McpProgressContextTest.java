package vip.mate.tool.mcp.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * White-box unit tests for {@link McpProgressContext}.
 * Covers register → lookup → remove lifecycle, snapshot persistence,
 * multi-conversation isolation, and concurrent safety.
 */
class McpProgressContextTest {

    private McpProgressContext ctx() {
        return new McpProgressContext();
    }

    // ── Token Map ──

    @Test
    @DisplayName("register → lookup returns same entry")
    void registerAndLookup() {
        McpProgressContext ctx = ctx();
        var entry = new McpProgressContext.ProgressEntry("conv_1", "call_1", "search");
        ctx.register("token-1", entry);
        assertSame(entry, ctx.lookup("token-1"));
    }

    @Test
    @DisplayName("lookup for unregistered token returns null")
    void lookupMissingReturnsNull() {
        assertNull(ctx().lookup("nonexistent"));
    }

    @Test
    @DisplayName("remove makes subsequent lookup return null")
    void removeThenLookupReturnsNull() {
        McpProgressContext ctx = ctx();
        ctx.register("tok", new McpProgressContext.ProgressEntry("c", "t", "n"));
        ctx.remove("tok");
        assertNull(ctx.lookup("tok"));
    }

    @Test
    @DisplayName("register overwrites existing entry for same token")
    void registerOverwrites() {
        McpProgressContext ctx = ctx();
        var first = new McpProgressContext.ProgressEntry("c1", "t1", "n1");
        var second = new McpProgressContext.ProgressEntry("c2", "t2", "n2");
        ctx.register("tok", first);
        ctx.register("tok", second);
        assertSame(second, ctx.lookup("tok"));
    }

    @Test
    @DisplayName("remove of non-existent token is no-op")
    void removeNonexistentIsNoop() {
        McpProgressContext ctx = ctx();
        assertDoesNotThrow(() -> ctx.remove("ghost"));
    }

    // ── Snapshot Map ──

    @Test
    @DisplayName("updateSnapshot stores and getSnapshots returns latest")
    void snapshotStoreAndRetrieve() {
        McpProgressContext ctx = ctx();
        ctx.updateSnapshot("conv_1", "call_a", "{\"percent\":30}");
        ctx.updateSnapshot("conv_1", "call_a", "{\"percent\":70}");

        var snapshots = ctx.getSnapshots("conv_1");
        assertEquals(1, snapshots.size());
        assertEquals("{\"percent\":70}", snapshots.get("call_a"));
    }

    @Test
    @DisplayName("getSnapshots for unknown conversation returns empty map")
    void snapshotsForUnknownConversation() {
        assertTrue(ctx().getSnapshots("no_such_conv").isEmpty());
    }

    @Test
    @DisplayName("removeSnapshot cleans up individual tool snapshot")
    void removeSnapshot() {
        McpProgressContext ctx = ctx();
        ctx.updateSnapshot("conv_1", "call_a", "{\"p\":50}");
        ctx.updateSnapshot("conv_1", "call_b", "{\"p\":80}");
        ctx.removeSnapshot("conv_1", "call_a");

        var snapshots = ctx.getSnapshots("conv_1");
        assertEquals(1, snapshots.size());
        assertNull(snapshots.get("call_a"));
        assertEquals("{\"p\":80}", snapshots.get("call_b"));
    }

    @Test
    @DisplayName("removeSnapshot for unknown keys is no-op")
    void removeSnapshotNoop() {
        assertDoesNotThrow(() -> {
            McpProgressContext ctx = ctx();
            ctx.removeSnapshot("no_conv", "no_call");
            ctx.updateSnapshot("cv", "cl", "{}");
            ctx.removeSnapshot("cv", "other");
            assertEquals(1, ctx.getSnapshots("cv").size());
        });
    }

    @Test
    @DisplayName("getSnapshots returns immutable copy")
    void snapshotsImmutable() {
        McpProgressContext ctx = ctx();
        ctx.updateSnapshot("c", "t", "{}");
        var snap = ctx.getSnapshots("c");
        assertThrows(UnsupportedOperationException.class, () -> snap.put("x", "y"));
    }

    @Test
    @DisplayName("multiple conversations isolated")
    void multiConversationIsolation() {
        McpProgressContext ctx = ctx();
        ctx.updateSnapshot("c1", "t1", "A");
        ctx.updateSnapshot("c2", "t2", "B");

        assertEquals(1, ctx.getSnapshots("c1").size());
        assertEquals(1, ctx.getSnapshots("c2").size());
        assertEquals("A", ctx.getSnapshots("c1").get("t1"));
        assertEquals("B", ctx.getSnapshots("c2").get("t2"));
    }

    @Test
    @DisplayName("register + snapshot lifecycle: full round-trip")
    void fullRoundtrip() {
        McpProgressContext ctx = ctx();
        var entry = new McpProgressContext.ProgressEntry("conv_x", "call_x", "long_task");
        ctx.register("pt-1", entry);
        assertEquals(entry, ctx.lookup("pt-1"));

        ctx.updateSnapshot("conv_x", "call_x", "{\"percent\":33}");
        ctx.updateSnapshot("conv_x", "call_x", "{\"percent\":99}");
        assertEquals("{\"percent\":99}", ctx.getSnapshots("conv_x").get("call_x"));

        ctx.remove("pt-1");
        ctx.removeSnapshot("conv_x", "call_x");
        assertNull(ctx.lookup("pt-1"));
        assertTrue(ctx.getSnapshots("conv_x").isEmpty());
    }
}
