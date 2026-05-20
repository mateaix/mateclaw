package vip.mate.agent.context;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.lang.Nullable;

import java.util.Map;

/**
 * Immutable value object that travels alongside an agent invocation describing
 * <em>where the request came from</em> — channel, conversation, requester,
 * workspace, and optional delivery target.
 *
 * <p>Replaces ad-hoc ThreadLocal threading (RFC-063 v1) with explicit Spring AI
 * {@link ToolContext} carriage (RFC-063r §2.1). The wither-style API enables
 * the agent runtime to enrich the origin (agentId, workspace) without mutation.
 *
 * <h2>Field evolution rule</h2>
 * <ul>
 *   <li>Only add — never delete; deprecate at least 90 days (covers approval TTL)
 *       before physical removal.</li>
 *   <li>Never rename — add a new field plus deprecate-old-field, double-write
 *       during the migration window.</li>
 *   <li>{@link JsonIgnoreProperties#ignoreUnknown()} guards forward/backward
 *       compatibility when older approval rows are deserialized after upgrades.</li>
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatOrigin(
        @Nullable Long agentId,
        @Nullable String conversationId,
        @Nullable String requesterId,
        @Nullable Long workspaceId,
        @Nullable String workspaceBasePath,
        @Nullable Long channelId,
        @Nullable ChannelTarget channelTarget,
        // True only when the agent invocation was triggered by the scheduled-job
        // runner. An explicit discriminator (rather than inferring from
        // requesterId/channelId) so the runtime can branch on "is this a cron
        // run" without coupling to factory internals.
        boolean cronOrigin,
        /**
         * Display name of the user that sent the inbound IM message. Used by
         * the prompt-context injector so the agent's system prompt can
         * personalise replies ("You are talking to {{senderName}}"). Null
         * for non-IM origins (web, cron). {@code requesterId} carries the
         * stable identifier; this one is purely the human-readable surface.
         */
        @Nullable String senderName,
        /**
         * Source channel type ("feishu" / "wecom" / "dingtalk" / ...).
         * Lets the agent know which platform it's responding on, e.g. to
         * tailor formatting or hint at supported features.
         */
        @Nullable String channelType,
        /**
         * Group / chat identifier for IM channels — distinguishes private
         * vs. group conversations. Null for 1:1 chats. Distinct from
         * {@link #channelTarget()} (which targets cron / proactive sends).
         */
        @Nullable String chatId
) {

    /** Key used when this origin is wrapped into a Spring AI {@link ToolContext}. */
    public static final String CTX_KEY = "mateclaw.chatOrigin";

    /** Sentinel used by AgentService default overloads where no origin is supplied. */
    public static final ChatOrigin EMPTY =
            new ChatOrigin(null, null, "", null, null, null, null, false, null, null, null);

    // ---------------- Factories per entry point ----------------

    public static ChatOrigin web(@Nullable String conversationId,
                                 @Nullable String requesterId,
                                 @Nullable Long workspaceId,
                                 @Nullable String workspaceBasePath) {
        return new ChatOrigin(null, conversationId,
                requesterId != null ? requesterId : "",
                workspaceId, workspaceBasePath, null, null, false, null, "web", null);
    }

    public static ChatOrigin cron(@Nullable String conversationId,
                                  @Nullable Long workspaceId,
                                  @Nullable String workspaceBasePath,
                                  @Nullable Long channelId,
                                  @Nullable ChannelTarget target) {
        return new ChatOrigin(null, conversationId, "system",
                workspaceId, workspaceBasePath, channelId, target, true, null, null, null);
    }

    // ---------------- Wither-style updates ----------------

    public ChatOrigin withAgent(@Nullable Long newAgentId) {
        return new ChatOrigin(newAgentId, conversationId, requesterId,
                workspaceId, workspaceBasePath, channelId, channelTarget, cronOrigin,
                senderName, channelType, chatId);
    }

    public ChatOrigin withWorkspace(@Nullable Long newWorkspaceId,
                                    @Nullable String newWorkspaceBasePath) {
        return new ChatOrigin(agentId, conversationId, requesterId,
                newWorkspaceId, newWorkspaceBasePath, channelId, channelTarget, cronOrigin,
                senderName, channelType, chatId);
    }

    public ChatOrigin withConversationId(@Nullable String newConversationId) {
        return new ChatOrigin(agentId, newConversationId, requesterId,
                workspaceId, workspaceBasePath, channelId, channelTarget, cronOrigin,
                senderName, channelType, chatId);
    }

    /**
     * Carry the inbound message's sender display name, source channel
     * type, and chat (group) id. Called by the channel-side origin
     * factory so prompt-context injection can show the agent "who"
     * is talking and "where".
     */
    public ChatOrigin withSender(@Nullable String newSenderName,
                                  @Nullable String newChannelType,
                                  @Nullable String newChatId) {
        return new ChatOrigin(agentId, conversationId, requesterId,
                workspaceId, workspaceBasePath, channelId, channelTarget, cronOrigin,
                newSenderName, newChannelType, newChatId);
    }

    // ---------------- Spring AI ToolContext interop ----------------

    /** Wrap this origin into a Spring AI {@link ToolContext} the runtime can pass to tools. */
    public ToolContext toToolContext() {
        return new ToolContext(Map.of(CTX_KEY, this));
    }

    /**
     * Read a {@link ChatOrigin} stored under {@link #CTX_KEY} in the given
     * {@link ToolContext}. Returns {@link #EMPTY} when {@code ctx} is null, has
     * no entry, or the value is not a ChatOrigin (defensive — keeps single-tool
     * callers safe even if wiring is partial).
     */
    public static ChatOrigin from(@Nullable ToolContext ctx) {
        if (ctx == null) return EMPTY;
        Object v = ctx.getContext().get(CTX_KEY);
        return v instanceof ChatOrigin co ? co : EMPTY;
    }
}
