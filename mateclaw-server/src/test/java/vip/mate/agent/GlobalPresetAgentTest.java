package vip.mate.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.repository.AgentMapper;
import vip.mate.exception.MateClawException;
import vip.mate.memory.MemoryProperties;
import vip.mate.memory.lifecycle.MemoryLifecycleMediator;
import vip.mate.memory.service.MemoryRecallTracker;
import vip.mate.workspace.conversation.repository.ConversationMapper;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 全局共享预置 Agent（workspace_id = 0，issue #567 / B-min）的只读语义测试。
 *
 * <p>覆盖：{@link AgentEntity#isGlobalPreset()} 的判定，以及 {@link AgentService}
 * 三条用户态写路径（create/update/delete）对全局预置的拒绝。绑定层守卫
 * （AgentBindingService.requireNotGlobalPreset）是同构镜像，此处不重复搭建其
 * 10 参构造，靠编译与相同逻辑保障。
 */
class GlobalPresetAgentTest {

    private AgentService newService(AgentMapper mapper) {
        return new AgentService(
                mapper,
                mock(AgentGraphBuilder.class),
                mock(MemoryRecallTracker.class),
                mock(MemoryLifecycleMediator.class),
                mock(MemoryProperties.class),
                mock(vip.mate.memory.identity.MemoryOwnerResolver.class),
                mock(ConversationMapper.class));
    }

    private AgentEntity agent(Long id, Long workspaceId) {
        AgentEntity e = new AgentEntity();
        e.setId(id);
        e.setWorkspaceId(workspaceId);
        e.setName("Some Agent");
        e.setEnabled(true);
        return e;
    }

    // ==================== isGlobalPreset ====================

    @Test
    @DisplayName("workspace_id=0 判定为全局预置；其它工作区/null 否")
    void isGlobalPresetDetection() {
        assertTrue(agent(1L, 0L).isGlobalPreset());
        assertFalse(agent(2L, 1L).isGlobalPreset());
        assertFalse(agent(3L, 5L).isGlobalPreset());
        assertFalse(agent(4L, null).isGlobalPreset());
    }

    // ==================== createAgent ====================

    @Test
    @DisplayName("createAgent 拒绝用户传入 workspace_id=0（堵住 X-Workspace-Id:0 漏洞）")
    void createAgentRejectsGlobalWorkspace() {
        AgentService service = newService(mock(AgentMapper.class));
        AgentEntity e = agent(null, 0L);
        assertThrows(MateClawException.class, () -> service.createAgent(e));
    }

    @Test
    @DisplayName("createAgent 允许普通工作区（workspace_id>0）")
    void createAgentAllowsNormalWorkspace() {
        AgentMapper mapper = mock(AgentMapper.class);
        when(mapper.selectCount(any())).thenReturn(0L); // requireUniqueName 通过
        AgentService service = newService(mapper);
        AgentEntity e = agent(null, 7L);
        assertDoesNotThrow(() -> service.createAgent(e));
        verify(mapper).insert(e);
    }

    // ==================== updateAgent ====================

    @Test
    @DisplayName("updateAgent 拒绝修改全局预置")
    void updateAgentRejectsGlobalPreset() {
        AgentMapper mapper = mock(AgentMapper.class);
        when(mapper.selectById(1L)).thenReturn(agent(1L, 0L));
        AgentService service = newService(mapper);

        AgentEntity incoming = agent(1L, 0L);
        incoming.setName("Renamed");
        assertThrows(MateClawException.class, () -> service.updateAgent(incoming));
        verify(mapper, never()).updateById(any(AgentEntity.class));
    }

    // ==================== deleteAgent ====================

    @Test
    @DisplayName("deleteAgent 拒绝删除全局预置")
    void deleteAgentRejectsGlobalPreset() {
        AgentMapper mapper = mock(AgentMapper.class);
        when(mapper.selectById(1L)).thenReturn(agent(1L, 0L));
        AgentService service = newService(mapper);

        assertThrows(MateClawException.class, () -> service.deleteAgent(1L));
        verify(mapper, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("deleteAgent 允许删除普通工作区 Agent")
    void deleteAgentAllowsNormalWorkspace() {
        AgentMapper mapper = mock(AgentMapper.class);
        when(mapper.selectById(2L)).thenReturn(agent(2L, 5L));
        AgentService service = newService(mapper);

        assertDoesNotThrow(() -> service.deleteAgent(2L));
        verify(mapper).deleteById(2L);
    }
}
