package vip.mate.cron;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import vip.mate.agent.context.ChannelTarget;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.repository.AgentMapper;
import vip.mate.cron.model.CronJobEntity;
import vip.mate.cron.model.DeliveryConfig;

/**
 * RFC-063r §2.2: factory that builds a {@link ChatOrigin} for a cron-triggered
 * agent invocation. Lives in {@code vip.mate.cron} so the dependency arrow
 * points {@code cron → agent} only — symmetric with
 * {@code ChannelChatOriginFactory} in {@code vip.mate.channel}.
 *
 * <p>workspaceId is reverse-resolved from {@code agent.workspaceId} (with the
 * legacy {@code 1L} fallback to keep behavior identical to
 * {@code CronJobService.executeJob}). workspaceBasePath is intentionally not
 * persisted — the value is derived at run time from the agent's workspace
 * configuration, matching the previous {@code ToolExecutionContext.workspaceBasePath()}
 * semantics.
 */
@Component
@RequiredArgsConstructor
public class CronChatOriginFactory {

    private final AgentMapper agentMapper;

    public ChatOrigin from(CronJobEntity job, String conversationId) {
        return from(job, conversationId, null);
    }

    public ChatOrigin from(CronJobEntity job, String conversationId, Long originMessageId) {
        AgentEntity agent = job.getAgentId() != null ? agentMapper.selectById(job.getAgentId()) : null;
        Long workspaceId = agent != null && agent.getWorkspaceId() != null ? agent.getWorkspaceId() : 1L;

        DeliveryConfig dc = job.getDeliveryConfig();
        ChannelTarget target = dc != null ? dc.toChannelTarget() : null;

        return ChatOrigin.cron(conversationId, workspaceId, /* workspaceBasePath */ null,
                job.getChannelId(), target).withOriginMessageId(originMessageId);
    }
}
