package vip.mate.channel.tool;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.function.Function;

/**
 * Convenience {@link ToolCallback} that wraps a {@code (name,
 * description, schema, handler)} tuple — saves channel-tool providers
 * from having to spell out the whole {@code ToolCallback} interface
 * for every handler.
 *
 * <p>Identical in spirit to the skill-runtime wrapper but kept in the
 * channel-tool domain so cross-domain refactors don't accidentally
 * couple the two.
 */
public class ChannelToolCallback implements ToolCallback {

    private final ToolDefinition definition;
    private final Function<String, String> handler;

    public ChannelToolCallback(String name,
                                String description,
                                String inputSchema,
                                Function<String, String> handler) {
        this.definition = ToolDefinition.builder()
                .name(name)
                .description(description)
                .inputSchema(inputSchema)
                .build();
        this.handler = handler;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return definition;
    }

    @Override
    public String call(String toolInput) {
        return handler.apply(toolInput);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return handler.apply(toolInput);
    }

    /**
     * Build a new callback that is identical in every way except it
     * carries the supplied {@code actualName}. Used by
     * {@link ChannelToolService} to apply the {@code _c<channelId>}
     * suffix without having the provider know about per-instance names.
     */
    public ToolCallback renamed(String actualName) {
        return new ChannelToolCallback(
                actualName,
                definition.description(),
                definition.inputSchema(),
                handler);
    }
}
