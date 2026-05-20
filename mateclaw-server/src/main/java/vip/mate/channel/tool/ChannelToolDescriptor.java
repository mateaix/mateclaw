package vip.mate.channel.tool;

/**
 * Static description of one channel-native tool — what
 * {@link ChannelToolProvider#describeTools()} returns. Used by
 * {@link ChannelToolService} to upsert {@code mate_tool} rows and by
 * the admin UI to render the tool in the Channel group before any
 * specific channel instance is materialised.
 *
 * <p>"Static" here means: no credentials needed, no network call —
 * pure metadata about a tool that this channel type COULD expose if
 * connected. The per-instance materialisation (binding the tool to a
 * specific {@code mate_channel} row + its SDK client) is the second
 * step performed by {@link ChannelToolProvider#createTools}.
 *
 * @param name             tool base name (e.g.
 *                         {@code "feishu_calendar_create_event"}).
 *                         Per-instance materialisation prefixes
 *                         {@code _c<channelId>} to keep the actual
 *                         registered tool name stable across CRUD —
 *                         see {@link ChannelToolService}.
 * @param displayName      human-readable label for UI
 * @param description      short description LLM uses to decide whether
 *                         to call this tool. Long-form usage notes
 *                         belong in a companion skill package.
 * @param inputSchema      JSON Schema string describing the tool's args
 * @param mutating         {@code true} for write operations. The tool
 *                         row defaults to {@code enabled=false} and
 *                         {@link ChannelToolService} seeds a DB rule
 *                         so that calls hit Guard / approval before
 *                         executing.
 * @param enabledByDefault should the {@code mate_tool} row be created
 *                         with {@code enabled=true}? Forced to
 *                         {@code false} when {@link #mutating()}.
 */
public record ChannelToolDescriptor(
        String name,
        String displayName,
        String description,
        String inputSchema,
        boolean mutating,
        boolean enabledByDefault) {

    public ChannelToolDescriptor {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("ChannelToolDescriptor.name must be non-blank");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("ChannelToolDescriptor.description must be non-blank");
        }
        if (inputSchema == null || inputSchema.isBlank()) {
            throw new IllegalArgumentException("ChannelToolDescriptor.inputSchema must be non-blank");
        }
        // Mutating tools always start disabled regardless of caller intent.
        if (mutating && enabledByDefault) {
            enabledByDefault = false;
        }
    }
}
