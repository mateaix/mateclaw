package vip.mate.tool.mcp.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpToolNameResolverTest {

    @Test
    @DisplayName("prefixedName follows mcp_<serverId>_<slug>_<hash6> shape")
    void prefixedNameShape() {
        String name = McpToolNameResolver.prefixedName(42L, "create_issue");
        assertTrue(name.startsWith("mcp_42_create_issue_"), "got: " + name);
        // hash6 occupies the last 6 chars; everything before the final '_' is
        // the slug (not the raw name) prefixed by serverId.
        String hash = name.substring(name.length() - 6);
        assertEquals(6, hash.length());
    }

    @Test
    @DisplayName("same raw name produces same prefixed name on the same server")
    void deterministicForSameInput() {
        String a = McpToolNameResolver.prefixedName(42L, "search");
        String b = McpToolNameResolver.prefixedName(42L, "search");
        assertEquals(a, b);
    }

    @Test
    @DisplayName("same raw name on different servers produces different prefixed names")
    void differentServerYieldsDifferentName() {
        String a = McpToolNameResolver.prefixedName(42L, "search");
        String b = McpToolNameResolver.prefixedName(43L, "search");
        assertNotEquals(a, b);
        assertTrue(a.startsWith("mcp_42_"));
        assertTrue(b.startsWith("mcp_43_"));
    }

    @Test
    @DisplayName("raw names that collapse to the same slug differ in the hash component")
    void slugCollisionsAreDistinguishedByHash() {
        // Without the hash, "a b" / "a_b" / "a/b" all slug to "a_b" and the
        // single-string binding model would silently collide.
        String a = McpToolNameResolver.prefixedName(42L, "a b");
        String b = McpToolNameResolver.prefixedName(42L, "a_b");
        String c = McpToolNameResolver.prefixedName(42L, "a/b");
        assertNotEquals(a, b);
        assertNotEquals(b, c);
        assertNotEquals(a, c);
        assertTrue(a.startsWith("mcp_42_a_b_"));
        assertTrue(b.startsWith("mcp_42_a_b_"));
        assertTrue(c.startsWith("mcp_42_a_b_"));
    }

    @Test
    @DisplayName("non-ASCII raw names get a stable 'tool' slug placeholder")
    void nonAsciiRawNameUsesPlaceholderSlug() {
        String name = McpToolNameResolver.prefixedName(42L, "查询订单");
        assertTrue(name.startsWith("mcp_42_tool_"), "got: " + name);
    }

    @Test
    @DisplayName("slug is truncated to 20 chars even for very long raw names")
    void slugTruncatedAtTwentyChars() {
        String longRaw = "abcdefghijklmnopqrstuvwxyz0123456789"; // 36 chars
        String name = McpToolNameResolver.prefixedName(42L, longRaw);
        // shape: mcp_42_<slug20>_<hash6>
        // verify slug portion is exactly 20 chars
        int firstSep = name.indexOf('_', "mcp_".length());
        int lastSep = name.lastIndexOf('_');
        String slug = name.substring(firstSep + 1, lastSep);
        assertEquals(20, slug.length());
    }

    @Test
    @DisplayName("blank raw name throws IllegalArgumentException")
    void blankRawNameRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> McpToolNameResolver.prefixedName(42L, ""));
        assertThrows(IllegalArgumentException.class,
                () -> McpToolNameResolver.prefixedName(42L, null));
    }

    @Test
    @DisplayName("parse round-trips serverId, slug, and hash6")
    void parseRoundTrip() {
        String name = McpToolNameResolver.prefixedName(42L, "create_issue");
        McpToolNameResolver.ParsedRef ref = McpToolNameResolver.parse(name);
        assertNotNull(ref);
        assertEquals(42L, ref.serverId());
        assertEquals("create_issue", ref.slug());
        assertEquals(6, ref.hash6().length());
        // hash6 of the same raw name reproduces — the cache reverse-lookup
        // path depends on this property.
        assertEquals(McpToolNameResolver.hash6("create_issue"), ref.hash6());
    }

    @Test
    @DisplayName("parse returns null for non-MCP names")
    void parseRejectsNonMcp() {
        assertNull(McpToolNameResolver.parse(null));
        assertNull(McpToolNameResolver.parse(""));
        assertNull(McpToolNameResolver.parse("web_search")); // builtin
        assertNull(McpToolNameResolver.parse("mcp_"));        // missing parts
        assertNull(McpToolNameResolver.parse("mcp_abc_x_yz")); // serverId not numeric
        assertNull(McpToolNameResolver.parse("mcp_42_search_xyz")); // hash too short
    }

    @Test
    @DisplayName("isMcpPrefixedName is a cheap routing check")
    void isMcpPrefixedName() {
        assertTrue(McpToolNameResolver.isMcpPrefixedName("mcp_42_search_aaaaaa"));
        assertFalse(McpToolNameResolver.isMcpPrefixedName(null));
        assertFalse(McpToolNameResolver.isMcpPrefixedName("web_search"));
    }
}
