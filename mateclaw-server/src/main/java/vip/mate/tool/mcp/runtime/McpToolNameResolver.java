package vip.mate.tool.mcp.runtime;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Single source of truth for MCP tool callback names.
 *
 * <p>Format: {@code mcp_<serverId>_<slug>_<hash6>} where:
 * <ul>
 *   <li>{@code <serverId>} — immutable {@code mate_mcp_server.id} (numeric
 *       Snowflake). Anchoring to the DB primary key (not the user-visible
 *       display name) makes display-name renames transparent to bindings.</li>
 *   <li>{@code <slug>} — first 20 chars of {@code [^a-z0-9_-]→'_'} on the
 *       lowercased raw tool name. Kept for human readability in logs / SQL.</li>
 *   <li>{@code <hash6>} — first 6 chars of base32-no-pad
 *       {@code SHA-256(raw_tool_name)}. Greatly reduces the chance of
 *       distinct raw names colliding under the same slug; residual collisions
 *       (probabilistic, not zero) are handled explicitly by
 *       {@link McpHashCollisionDetector} at registration and picker emission
 *       time — never relied on as a uniqueness guarantee.</li>
 * </ul>
 *
 * <p>Length budget: {@code mcp_} (4) + serverId (≤19) + sep + slug (≤20) +
 * sep + hash6 (6) = ≤51 chars, comfortably under any 64-char tool-name caps
 * downstream tool engines may enforce.
 *
 * <p><b>The format is not a 1:1 string-only inverse of the raw name.</b>
 * The slug stage is lossy (multiple raw names can map to the same slug;
 * non-ASCII names map to {@code "tool"}). The hash makes the full key
 * statistically unique within {@code (serverId, raw_tool_name)} space, but
 * recovering the raw name from the prefixed name alone is not possible.
 * Reversal must go through the per-server cached tools list: given
 * {@code (serverId, hash6)}, find the cached tool whose
 * {@code SHA-256(raw)} hashes to the same prefix.
 */
public final class McpToolNameResolver {

    public static final String PREFIX = "mcp_";
    public static final int SLUG_MAX = 20;
    public static final int HASH_LEN = 6;

    private static final Pattern UNSAFE = Pattern.compile("[^a-z0-9_-]");
    // RFC 4648 base32 lowercase, no padding. Lowercase keeps the prefixed
    // name fully lowercase + digits + dashes — friendly to URL paths,
    // filenames, log greps, and case-insensitive systems.
    private static final char[] BASE32 = "abcdefghijklmnopqrstuvwxyz234567".toCharArray();

    private McpToolNameResolver() {}

    /** Build the prefixed callback name for a given (serverId, raw tool name) pair. */
    public static String prefixedName(long serverId, String rawToolName) {
        if (rawToolName == null || rawToolName.isBlank()) {
            throw new IllegalArgumentException("rawToolName must not be blank");
        }
        return PREFIX + serverId + "_" + slug(rawToolName) + "_" + hash6(rawToolName);
    }

    /**
     * Parse a prefixed name into its components. Returns {@code null} if the
     * input does not match the MCP prefix shape — callers use this to route
     * lookups between bridged MCP names and other namespaces.
     *
     * <p>Note that {@link ParsedRef} intentionally does not include the raw
     * tool name: that requires a cache lookup (see class Javadoc).
     */
    public static ParsedRef parse(String prefixedName) {
        if (prefixedName == null || !prefixedName.startsWith(PREFIX)) {
            return null;
        }
        int firstSep = prefixedName.indexOf('_', PREFIX.length());
        int lastSep = prefixedName.lastIndexOf('_');
        if (firstSep < 0 || lastSep <= firstSep) {
            return null;
        }
        String serverIdStr = prefixedName.substring(PREFIX.length(), firstSep);
        String slug = prefixedName.substring(firstSep + 1, lastSep);
        String hash = prefixedName.substring(lastSep + 1);
        if (hash.length() != HASH_LEN || slug.isEmpty()) {
            return null;
        }
        long serverId;
        try {
            serverId = Long.parseLong(serverIdStr);
        } catch (NumberFormatException e) {
            return null;
        }
        return new ParsedRef(serverId, slug, hash);
    }

    /** Cheap O(prefix length) check used by routing code paths. */
    public static boolean isMcpPrefixedName(String name) {
        return name != null && name.startsWith(PREFIX);
    }

    /** Reproduce the hash6 of a known raw name — used for cache reverse lookup. */
    public static String hash6(String rawToolName) {
        if (rawToolName == null) {
            throw new IllegalArgumentException("rawToolName must not be null");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(rawToolName.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(HASH_LEN);
            for (int i = 0; sb.length() < HASH_LEN; i++) {
                sb.append(BASE32[digest[i] & 0x1F]);
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by every standard Java runtime — reaching
            // this branch means the JVM is misconfigured and the application
            // has bigger problems than tool naming.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String slug(String raw) {
        String s = UNSAFE.matcher(raw.toLowerCase(Locale.ROOT)).replaceAll("_");
        if (s.length() > SLUG_MAX) {
            s = s.substring(0, SLUG_MAX);
        }
        // A raw name composed entirely of non-ASCII chars (e.g. pure CJK)
        // collapses to underscores and then to an empty slug after trimming;
        // give it a stable placeholder so the prefixed name is still
        // well-formed and the hash carries the actual identity.
        if (s.replace("_", "").isEmpty()) {
            return "tool";
        }
        return s;
    }

    /**
     * Decoded prefix components. {@code rawToolName} is intentionally absent
     * — recover it via the per-server tools cache when needed.
     */
    public record ParsedRef(long serverId, String slug, String hash6) {}
}
