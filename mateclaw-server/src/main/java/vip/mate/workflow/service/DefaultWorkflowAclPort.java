package vip.mate.workflow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Component;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.repository.AgentMapper;
import vip.mate.channel.model.ChannelEntity;
import vip.mate.channel.repository.ChannelMapper;
import vip.mate.workflow.compiler.WorkflowAclPort;

/**
 * Production binding for {@link WorkflowAclPort}. Reads agents from
 * {@code mate_agent}, channels from {@code mate_channel}, and treats every
 * non-blank {@code employeeId} as a workspace member — until a real
 * "human employee" registry exists in the system, the workflow's
 * {@code employeeId} is interpreted as the agent id of the agent that owns
 * the memory file.
 */
@Component
public class DefaultWorkflowAclPort implements WorkflowAclPort {

    private final AgentMapper agentMapper;
    private final ChannelMapper channelMapper;

    public DefaultWorkflowAclPort(AgentMapper agentMapper, ChannelMapper channelMapper) {
        this.agentMapper = agentMapper;
        this.channelMapper = channelMapper;
    }

    @Override
    public boolean agentExists(long workspaceId, String agentName) {
        if (agentName == null || agentName.isBlank()) return false;
        Long count = agentMapper.selectCount(new LambdaQueryWrapper<AgentEntity>()
                .eq(AgentEntity::getName, agentName.trim())
                .eq(AgentEntity::getEnabled, true));
        return count != null && count > 0;
    }

    @Override
    public boolean agentIdExists(long workspaceId, long agentId) {
        AgentEntity row = agentMapper.selectById(agentId);
        return row != null && Boolean.TRUE.equals(row.getEnabled());
    }

    @Override
    public boolean channelAllowed(long workspaceId, String channelName) {
        if (channelName == null || channelName.isBlank()) return false;
        Long count = channelMapper.selectCount(new LambdaQueryWrapper<ChannelEntity>()
                .eq(ChannelEntity::getChannelType, channelName.trim())
                .eq(ChannelEntity::getEnabled, true));
        return count != null && count > 0;
    }

    @Override
    public boolean employeeInWorkspace(long workspaceId, String employeeId) {
        if (employeeId == null || employeeId.isBlank()) return false;
        try {
            long parsed = Long.parseLong(employeeId);
            return agentIdExists(workspaceId, parsed);
        } catch (NumberFormatException e) {
            // Non-numeric employeeId — let the runtime fail loudly rather
            // than silently passing publish-time ACL.
            return false;
        }
    }
}
