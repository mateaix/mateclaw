package vip.mate.channel.tool;

import java.util.Map;

/**
 * Context handed to {@link ChannelToolProvider#createTools(ChannelToolContext)}
 * when materialising tools for one specific {@code mate_channel} row.
 *
 * <p>{@link #channelId()} alone is enough for the provider to call its
 * SDK-client factory (e.g. {@code FeishuClientFactory.client(channelId)});
 * the remaining fields are convenience for handlers that want to
 * default any "who should I attribute this to" or "which app are we
 * acting on behalf of" parameters.
 *
 * @param channelId   {@code mate_channel.id} — the only required field
 * @param channelName display name of the channel row (for log clarity)
 * @param channelType {@code mate_channel.channel_type} — matches
 *                    {@link ChannelToolProvider#channelType()}
 * @param agentId     the {@code mate_agent.id} this channel routes
 *                    inbound messages to, may be null when the channel
 *                    is unbound
 * @param config      parsed {@code mate_channel.config_json} as a map
 *                    (e.g. {@code app_id}, {@code app_secret},
 *                    {@code domain}). Provided so handlers don't each
 *                    re-parse the JSON.
 */
public record ChannelToolContext(
        Long channelId,
        String channelName,
        String channelType,
        Long agentId,
        Map<String, Object> config) {

    public ChannelToolContext {
        if (channelId == null) {
            throw new IllegalArgumentException("ChannelToolContext.channelId must not be null");
        }
        if (channelType == null || channelType.isBlank()) {
            throw new IllegalArgumentException("ChannelToolContext.channelType must be non-blank");
        }
        if (config == null) {
            config = Map.of();
        }
    }
}
