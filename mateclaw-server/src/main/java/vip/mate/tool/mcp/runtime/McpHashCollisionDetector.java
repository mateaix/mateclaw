package vip.mate.tool.mcp.runtime;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-server hash collision detector for MCP tool names.
 *
 * <p>{@link McpToolNameResolver}'s 30-bit hash makes name collisions
 * statistically rare but not impossible. The detector runs the same
 * input set through the resolver and reports which raw names collide on
 * the same prefixed name, so two callers can agree on which entries are
 * "bindable" and which are not:
 *
 * <ul>
 *   <li>{@code McpClientManager} consults the detector before registering
 *       runtime callbacks, skipping the second of any colliding pair so
 *       {@link org.springframework.ai.tool.ToolCallback} names stay unique
 *       in the runtime tool set.</li>
 *   <li>{@code AvailableToolService} consults the detector when emitting
 *       picker DTOs, marking colliding entries {@code available=false}
 *       with reason {@code HASH_COLLISION} so the UI disables them.</li>
 * </ul>
 *
 * <p>Sharing the detector keeps these two views in lockstep — without it,
 * the picker could offer a tool whose runtime callback was silently
 * skipped, letting the user save a binding that resolves to nothing at
 * chat time.
 *
 * <p>Stateless and thread-safe.
 */
public final class McpHashCollisionDetector {

    private McpHashCollisionDetector() {}

    /**
     * Decide which raw tool names are bindable for a given server.
     *
     * <p>The first occurrence of each prefixed name wins; later raw names
     * that hash to the same prefix are recorded as collided. Iteration
     * order of {@code rawToolNames} therefore determines which raw name
     * is treated as canonical — callers should pass a stable order
     * (typically the order returned by {@code listTools()}).
     *
     * @return one entry per non-blank input raw name, in input order
     */
    public static List<Decision> classify(long serverId, Collection<String> rawToolNames) {
        if (rawToolNames == null || rawToolNames.isEmpty()) {
            return List.of();
        }
        Map<String, String> firstRawByPrefixed = new LinkedHashMap<>();
        List<Decision> out = new ArrayList<>(rawToolNames.size());
        for (String raw : rawToolNames) {
            if (raw == null || raw.isBlank()) {
                // Defensive: an MCP server shouldn't surface a blank tool name,
                // but if it does, drop it instead of letting resolver throw.
                continue;
            }
            String prefixed = McpToolNameResolver.prefixedName(serverId, raw);
            String prior = firstRawByPrefixed.putIfAbsent(prefixed, raw);
            if (prior == null) {
                out.add(new Decision(raw, prefixed, true, null));
            } else if (prior.equals(raw)) {
                // Same raw name appearing twice in the input — duplicate
                // declaration upstream, not a collision. Keep the first.
                out.add(new Decision(raw, prefixed, false, "DUPLICATE_RAW_NAME"));
            } else {
                out.add(new Decision(raw, prefixed, false, "HASH_COLLISION:" + prior));
            }
        }
        return out;
    }

    /**
     * One decision per raw tool name.
     *
     * @param rawToolName      name as discovered from the MCP server
     * @param prefixedName     resolved {@code mcp_<serverId>_<slug>_<hash6>}
     * @param bindable         {@code true} → runtime should register this
     *                         callback and the picker should offer it as
     *                         {@code available=true}; {@code false} → both
     *                         must skip / disable it
     * @param unavailableReason machine-readable cause when {@code !bindable}
     */
    public record Decision(String rawToolName, String prefixedName,
                           boolean bindable, String unavailableReason) {}
}
