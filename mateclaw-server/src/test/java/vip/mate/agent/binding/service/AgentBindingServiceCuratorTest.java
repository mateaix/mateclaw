package vip.mate.agent.binding.service;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.mate.agent.binding.model.AgentSkillBinding;
import vip.mate.agent.binding.repository.AgentProviderPreferenceMapper;
import vip.mate.agent.binding.repository.AgentSkillBindingMapper;
import vip.mate.agent.binding.repository.AgentToolBindingMapper;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.repository.AgentMapper;
import vip.mate.skill.acp.AcpSkillBridge;
import vip.mate.skill.lifecycle.BlockedByBindingRow;
import vip.mate.skill.lifecycle.ConfirmRequiredException;
import vip.mate.skill.model.SkillEntity;
import vip.mate.skill.repository.SkillMapper;
import vip.mate.skill.runtime.SkillRuntimeService;
import vip.mate.tool.service.AvailableToolService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Covers the lifecycle-curator support queries on {@link AgentBindingService}:
 * the binding hard guard and the manual-archive agent lookup.
 */
@ExtendWith(MockitoExtension.class)
class AgentBindingServiceCuratorTest {

    @Mock
    private AgentSkillBindingMapper skillBindingMapper;
    @Mock
    private AgentToolBindingMapper toolBindingMapper;
    @Mock
    private AgentProviderPreferenceMapper providerPreferenceMapper;
    @Mock
    private vip.mate.agent.binding.repository.AgentWikiKbBindingMapper kbBindingMapper;
    @Mock
    private vip.mate.wiki.repository.WikiKnowledgeBaseMapper kbMapper;
    @Mock
    private SkillRuntimeService skillRuntimeService;
    @Mock
    private AvailableToolService availableToolService;
    @Mock
    private AgentMapper agentMapper;
    @Mock
    private SkillMapper skillMapper;
    @Mock
    private AcpSkillBridge acpSkillBridge;

    private AgentBindingService service;

    private final LocalDateTime now = LocalDateTime.now();

    @BeforeAll
    static void initTableInfo() {
        // Lambda wrappers resolve column names from MyBatis-Plus's static
        // TableInfo cache; trigger it manually for this plain unit test.
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new Configuration(), "");
        TableInfoHelper.initTableInfo(assistant, AgentEntity.class);
        TableInfoHelper.initTableInfo(assistant, AgentSkillBinding.class);
        TableInfoHelper.initTableInfo(assistant, SkillEntity.class);
    }

    @BeforeEach
    void setUp() {
        service = new AgentBindingService(skillBindingMapper, toolBindingMapper, providerPreferenceMapper,
                kbBindingMapper, kbMapper,
                skillRuntimeService, availableToolService, agentMapper, skillMapper, acpSkillBridge);
    }

    private AgentEntity agent(long id, String name) {
        AgentEntity a = new AgentEntity();
        a.setId(id);
        a.setName(name);
        a.setEnabled(true);
        return a;
    }

    private AgentSkillBinding binding(long skillId, long agentId) {
        AgentSkillBinding b = new AgentSkillBinding();
        b.setSkillId(skillId);
        b.setAgentId(agentId);
        b.setEnabled(true);
        return b;
    }

    private SkillEntity skill(long id, String name, LocalDateTime lastActivity) {
        SkillEntity s = new SkillEntity();
        s.setId(id);
        s.setName(name);
        s.setSkillType("dynamic");
        s.setBuiltin(false);
        s.setPinned(false);
        s.setLastActivityAt(lastActivity);
        s.setCreateTime(lastActivity);
        return s;
    }

    @Test
    void onlyBindingsToEnabledAgentsCountAsProtected() {
        when(agentMapper.selectList(any())).thenReturn(List.of(agent(1L, "Alpha"), agent(2L, "Beta")));
        // skill 10 bound to enabled agent 1; skill 20 bound to a non-enabled agent 99.
        when(skillBindingMapper.selectList(any()))
                .thenReturn(List.of(binding(10L, 1L), binding(20L, 99L)));

        Set<Long> protectedIds = service.skillIdsBoundToEnabledAgents();

        assertEquals(Set.of(10L), protectedIds);
    }

    @Test
    void blockedByBindingCandidatesCarrySkillDetailAndDaysIdle() {
        when(agentMapper.selectList(any())).thenReturn(List.of(agent(1L, "Alpha")));
        when(skillBindingMapper.selectList(any())).thenReturn(List.of(binding(10L, 1L)));
        when(skillMapper.selectBatchIds(any()))
                .thenReturn(List.of(skill(10L, "weekly-report", now.minusDays(50))));

        List<BlockedByBindingRow> rows = service.blockedByBindingCandidates(now);

        assertEquals(1, rows.size());
        assertEquals(10L, rows.get(0).skillId());
        assertEquals("weekly-report", rows.get(0).name());
        assertEquals(50L, rows.get(0).daysIdle());
        assertTrue(rows.get(0).agentIds().contains(1L));
    }

    @Test
    void enabledAgentsBoundToSkillListsTheAffectedAgents() {
        when(skillBindingMapper.selectList(any())).thenReturn(List.of(binding(10L, 1L)));
        when(agentMapper.selectList(any())).thenReturn(List.of(agent(1L, "Alpha")));

        List<ConfirmRequiredException.AgentRow> agents = service.enabledAgentsBoundToSkill(10L);

        assertEquals(1, agents.size());
        assertEquals(1L, agents.get(0).id());
        assertEquals("Alpha", agents.get(0).name());
    }

    @Test
    void noEnabledAgentsMeansNothingIsProtected() {
        when(agentMapper.selectList(any())).thenReturn(List.of());

        assertTrue(service.skillIdsBoundToEnabledAgents().isEmpty());
    }
}
