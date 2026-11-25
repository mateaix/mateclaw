package vip.mate.team.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import vip.mate.exception.MateClawException;
import vip.mate.team.model.AgentTeamEntity;
import vip.mate.team.repository.AgentTeamMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TeamCoordinatorPolicyTest {
    @BeforeAll static void initTables() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""), AgentTeamEntity.class);
    }
    @Test void legacyAndStrictSettings() {
        assertFalse(TeamCoordinatorPolicy.enabled(null));
        assertFalse(TeamCoordinatorPolicy.enabled("{\"other\":1}"));
        assertTrue(TeamCoordinatorPolicy.enabled("{\"coordinatorMode\":true}"));
        assertFalse(TeamCoordinatorPolicy.enabled("{\"coordinatorMode\":\"true\"}"));
        assertFalse(TeamCoordinatorPolicy.enabled("legacy invalid JSON"));
        assertDoesNotThrow(() -> TeamCoordinatorPolicy.requireNative(" native "));
        assertThrows(vip.mate.exception.MateClawException.class, () -> TeamCoordinatorPolicy.mergeSettings("broken", "{}"));
        assertEquals("{\"other\":1,\"coordinatorMode\":true}", TeamCoordinatorPolicy.mergeSettings("{\"other\":1}", "{\"coordinatorMode\":true}"));
        assertThrows(vip.mate.exception.MateClawException.class, () -> TeamCoordinatorPolicy.mergeSettings(null, "[]"));
        assertThrows(vip.mate.exception.MateClawException.class, () -> TeamCoordinatorPolicy.mergeSettings(null, ""));
        assertThrows(vip.mate.exception.MateClawException.class, () -> TeamCoordinatorPolicy.mergeSettings(null, "{} {}"));
    }
    @Test void onlyLeadRestrictedAndBindingsNeedNotBeChanged() {
        AgentTeamMapper mapper = mock(AgentTeamMapper.class);
        TeamCoordinatorPolicy policy = new TeamCoordinatorPolicy(mapper);
        AgentTeamEntity team = new AgentTeamEntity(); team.setLeadAgentId(1L); team.setSettings("{\"coordinatorMode\":true}");
        when(mapper.selectOne(any())).thenReturn(team);
        assertTrue(policy.isCoordinator(1L));
        assertFalse(policy.isCoordinator(2L));
        var lead = new vip.mate.agent.model.AgentEntity(); lead.setId(1L); lead.setRuntimeType("dsh");
        assertThrows(vip.mate.exception.MateClawException.class, () -> policy.requireSupportedRuntime(lead));
        lead.setId(2L); assertDoesNotThrow(() -> policy.requireSupportedRuntime(lead));
        assertTrue(TeamCoordinatorPolicy.TOOLS.contains("team_tasks"));
        for (String forbidden : new String[]{"delegateToAgent", "delegateParallel", "delegateAsync", "taskOutput", "read_file", "exec", "load_skill", "setGoal"})
            assertFalse(TeamCoordinatorPolicy.TOOLS.contains(forbidden));
        team.setSettings("{\"coordinatorMode\":false}"); assertFalse(policy.isCoordinator(1L));
    }

    @Test void pausedTeamStillKeepsSavedCoordinatorScope() {
        var mapper = mock(AgentTeamMapper.class);
        var team = new AgentTeamEntity(); team.setLeadAgentId(1L); team.setStatus("paused"); team.setSettings("{\"coordinatorMode\":true}");
        when(mapper.selectOne(argThat(wrapper -> !wrapper.getSqlSegment().contains("status")))).thenReturn(team);
        assertTrue(new TeamCoordinatorPolicy(mapper).isCoordinator(1L));
    }
    @Test void errorsCarryLocalizableBusinessKeys() {
        assertEquals("err.team.coordinator_native_required",assertThrows(MateClawException.class,
                () -> TeamCoordinatorPolicy.requireNative("dsh")).getMsgKey());
        assertEquals("err.team.coordinator_settings_invalid",assertThrows(MateClawException.class,
                () -> TeamCoordinatorPolicy.mergeSettings(null,"{\"coordinatorMode\":1}")).getMsgKey());
    }
}
