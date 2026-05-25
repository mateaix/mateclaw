package vip.mate.tool.mcp.runtime;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpHashCollisionDetectorTest {

    /**
     * A pair of raw names with identical 20-char slug AND identical hash6 —
     * found once at startup via birthday-style search. The constant prefix
     * truncates the slug to {@code "xxxxxxxxxxxxxxxxxxxx"} so the only
     * remaining variable in {@code prefixedName} is the hash, and on a
     * 30-bit hash space the birthday paradox finds a collision in
     * ~32k tries on average.
     *
     * <p>Failing fast at {@link BeforeAll} keeps the actual test honest —
     * a hung search would surface as a build hang, not a silent skip.
     */
    private static String[] HASH_COLLIDING_PAIR;

    @BeforeAll
    static void findHashCollidingPair() {
        String slugAnchor = "xxxxxxxxxxxxxxxxxxxx"; // exactly 20 chars → fills the slug budget
        Map<String, String> hashToRaw = new HashMap<>();
        for (int i = 0; i < 1_000_000; i++) {
            String raw = slugAnchor + i;
            String hash = McpToolNameResolver.hash6(raw);
            String prior = hashToRaw.put(hash, raw);
            if (prior != null && !prior.equals(raw)) {
                HASH_COLLIDING_PAIR = new String[]{prior, raw};
                return;
            }
        }
        // Astronomically unlikely; only happens if hash6's distribution is
        // catastrophically bad (test serves as a smoke check on resolver too).
        throw new IllegalStateException("No hash collision found in 1M tries — resolver hash distribution may be broken");
    }

    @Test
    @DisplayName("distinct raw names that don't hash-collide are all bindable")
    void noCollisionAllBindable() {
        List<McpHashCollisionDetector.Decision> decisions =
                McpHashCollisionDetector.classify(42L, List.of("search", "read_file", "create_issue"));
        assertEquals(3, decisions.size());
        for (McpHashCollisionDetector.Decision d : decisions) {
            assertTrue(d.bindable(), "expected bindable for " + d.rawToolName());
            assertEquals(McpToolNameResolver.prefixedName(42L, d.rawToolName()), d.prefixedName());
        }
    }

    @Test
    @DisplayName("duplicate raw names within one server only bind once")
    void duplicateRawNameSecondInstanceIsNotBindable() {
        // MCP servers are not supposed to surface the same name twice, but be
        // defensive — drop the second declaration with a clear reason.
        List<McpHashCollisionDetector.Decision> decisions =
                McpHashCollisionDetector.classify(42L, List.of("search", "search"));
        assertEquals(2, decisions.size());
        assertTrue(decisions.get(0).bindable());
        assertFalse(decisions.get(1).bindable());
        assertEquals("DUPLICATE_RAW_NAME", decisions.get(1).unavailableReason());
    }

    @Test
    @DisplayName("blank or null raw names are dropped silently")
    void blankRawNamesAreSkipped() {
        List<McpHashCollisionDetector.Decision> decisions =
                McpHashCollisionDetector.classify(42L,
                        Arrays.asList("search", null, "", "  "));
        assertEquals(1, decisions.size());
        assertEquals("search", decisions.get(0).rawToolName());
    }

    @Test
    @DisplayName("hash collision: the second raw name is flagged with a reason carrying the prior raw")
    void hashCollisionFlagsSecondEntry() {
        assertNotNull(HASH_COLLIDING_PAIR, "@BeforeAll should have populated a colliding pair");
        String a = HASH_COLLIDING_PAIR[0];
        String b = HASH_COLLIDING_PAIR[1];

        // Sanity: the pair really does collide on the prefixed name.
        assertNotEquals(a, b);
        assertEquals(McpToolNameResolver.prefixedName(42L, a),
                McpToolNameResolver.prefixedName(42L, b));

        List<McpHashCollisionDetector.Decision> decisions =
                McpHashCollisionDetector.classify(42L, List.of(a, b));
        assertEquals(2, decisions.size());
        assertTrue(decisions.get(0).bindable());
        assertEquals(a, decisions.get(0).rawToolName());
        assertFalse(decisions.get(1).bindable());
        assertTrue(decisions.get(1).unavailableReason().startsWith("HASH_COLLISION:"),
                "got reason: " + decisions.get(1).unavailableReason());
        // The reason carries the prior raw so the operator can map back to
        // the upstream tool to rename.
        assertTrue(decisions.get(1).unavailableReason().contains(a));
    }

    /** Exposes the colliding pair to other tests in the same package. */
    static String[] hashCollidingPair() {
        return HASH_COLLIDING_PAIR;
    }

    @Test
    @DisplayName("two raw names same on different servers do not collide (anchored to serverId)")
    void crossServerNotACollision() {
        List<McpHashCollisionDetector.Decision> a =
                McpHashCollisionDetector.classify(42L, List.of("search"));
        List<McpHashCollisionDetector.Decision> b =
                McpHashCollisionDetector.classify(43L, List.of("search"));
        assertTrue(a.get(0).bindable());
        assertTrue(b.get(0).bindable());
        assertNotEquals(a.get(0).prefixedName(), b.get(0).prefixedName());
    }

}
