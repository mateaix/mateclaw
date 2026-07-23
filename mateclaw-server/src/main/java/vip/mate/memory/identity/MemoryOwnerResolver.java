package vip.mate.memory.identity;

import org.springframework.stereotype.Component;
import vip.mate.agent.context.ChatOrigin;

/**
 * Resolves the {@code owner_key} that conversation-derived memory should be
 * attributed to, from the request's {@link ChatOrigin}.
 *
 * <p>The key is a prefixed string so a single agent shared across surfaces
 * keeps every subject's memory separate:
 * <ul>
 *   <li>Web console  → {@code user:<requesterId>} (the MateClaw username)</li>
 *   <li>IM channels  → {@code <channelType>:<senderId>} (feishu/dingtalk/…)</li>
 *   <li>WebChat / 3rd-party API → {@code api:<visitorId|endUserId>}</li>
 *   <li>Cron / system / unknown → {@link #SYSTEM_OWNER}</li>
 * </ul>
 *
 * The cron/system fallback is deliberate: it keeps unattributed writes out of
 * any real user's PERSONAL bucket (which would otherwise be a black hole that
 * nobody can read) — such writes are expected to be TEAM-scoped instead.
 *
 * <p>This owner key is one of several per-visitor identity namespaces MateClaw
 * uses: conversation attribution uses {@code webchat:<visitorId>}, audit uses
 * {@code webchat:<channelId>:<visitorId>}, and MCP on-behalf-of forwarding uses
 * {@code <trust>:<subject>}. They are deliberately separate concerns — see the
 * "Identity namespaces" section of docs mcp.md for the mapping and correlation
 * rule. Do not introduce a new prefix format without updating that table.
 *
 * @author MateClaw Team
 */
@Component
public class MemoryOwnerResolver {

    /** Owner key used for cron-triggered and identity-less invocations. */
    public static final String SYSTEM_OWNER = "system";

    /**
     * Resolve the owner key for the given origin. Never returns null; falls
     * back to {@link #SYSTEM_OWNER} when no usable identity is present.
     */
    public String resolve(ChatOrigin origin) {
        if (origin == null || origin.cronOrigin()) {
            return SYSTEM_OWNER;
        }
        String requester = origin.requesterId();
        if (requester == null || requester.isBlank() || SYSTEM_OWNER.equals(requester)) {
            return SYSTEM_OWNER;
        }
        String channel = origin.channelType();
        if (channel == null || channel.isBlank() || "web".equals(channel)) {
            // Web console (or a degraded origin with no channel): the requester
            // id is already the MateClaw username.
            return "user:" + requester;
        }
        // IM / api origins: the requester id is the external sender id; prefix
        // with the channel type so two platforms can't collide on the same id.
        return channel + ":" + requester;
    }
}
