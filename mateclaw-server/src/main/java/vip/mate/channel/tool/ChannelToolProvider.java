package vip.mate.channel.tool;

import org.springframework.ai.tool.ToolCallback;

import java.util.List;

/**
 * SPI implemented once per channel type that exposes platform-native
 * capabilities (e.g. Feishu calendar / docx, WeCom approval, DingTalk
 * task) as Agent tools — avoiding the need for each user to also
 * configure a separate MCP server with duplicate credentials.
 *
 * <p>Implementations are plain Spring beans. {@link ChannelToolService}
 * collects every {@code ChannelToolProvider} bean at startup, indexed
 * by {@link #channelType()}; channel CRUD then reconciles per
 * {@code mate_channel} row by calling {@link #describeTools()} (for
 * the static catalog → DB upsert) and {@link #createTools} (for the
 * per-instance ToolCallback materialisation).
 *
 * <p>Implementations must NOT touch credentials in
 * {@link #describeTools()} — that path is invoked even when no channel
 * is configured. Credential access is restricted to
 * {@link #createTools(ChannelToolContext)} which receives an already-
 * validated context.
 */
public interface ChannelToolProvider {

    /**
     * Channel type this provider serves — matches
     * {@code ChannelAdapter.getChannelType()} (e.g. {@code "feishu"}).
     * Used by {@link ChannelToolService} for routing.
     */
    String channelType();

    /**
     * Static catalogue of every tool this channel type COULD expose.
     * Pure metadata — no credentials, no I/O. Called once at startup
     * to seed {@code mate_tool} rows for the admin UI.
     */
    List<ChannelToolDescriptor> describeTools();

    /**
     * Materialise per-instance {@link ToolCallback} instances for the
     * given channel row. Invoked by {@link ChannelToolService} during
     * reconcile; the returned callbacks are registered into
     * {@code ToolRegistry} via
     * {@code ToolRegistry.registerPluginTool(...)}.
     *
     * <p>Each returned callback's {@code getToolDefinition().name()}
     * must match a descriptor in {@link #describeTools()} (the service
     * will rename to the per-instance actual name).
     */
    List<ToolCallback> createTools(ChannelToolContext context);
}
