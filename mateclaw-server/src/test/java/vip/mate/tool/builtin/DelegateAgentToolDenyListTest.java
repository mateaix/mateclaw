package vip.mate.tool.builtin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.agent.AgentService;
import vip.mate.agent.delegation.SubagentRegistry;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.repository.AgentMapper;
import vip.mate.audit.service.AuditEventService;
import vip.mate.channel.web.ChatStreamTracker;
import vip.mate.workspace.conversation.ConversationService;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Coverage for the deny-list expansion + spawn-pause integration on
 * {@link DelegateAgentTool}. Builds the tool by hand so we can poke
 * private final fields without spinning up Mockito's full {@code @InjectMocks}
 * machinery.
 */
class DelegateAgentToolDenyListTest {

    private DelegateAgentTool tool;
    private SubagentRegistry registry;
    private AgentMapper agentMapper;

    @BeforeAll
    static void initMyBatisPlusCache() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new org.apache.ibatis.session.Configuration(), ""),
                AgentEntity.class);
    }

    @BeforeEach
    void setUp() {
        AgentService agentService = mock(AgentService.class);
        agentMapper = mock(AgentMapper.class);
        ChatStreamTracker streamTracker = mock(ChatStreamTracker.class);
        ConversationService conversationService = mock(ConversationService.class);
        ObjectMapper objectMapper = new ObjectMapper();
        registry = new SubagentRegistry();
        AuditEventService auditEventService = mock(AuditEventService.class);
        vip.mate.task.AsyncTaskService asyncTaskService = mock(vip.mate.task.AsyncTaskService.class);

        tool = new DelegateAgentTool(agentService, agentMapper, streamTracker, conversationService,
                objectMapper, registry, auditEventService, asyncTaskService);
    }

    @AfterEach
    void cleanup() {
        while (DelegationContext.currentDepth() > 0) {
            DelegationContext.exit();
        }
        ToolExecutionContext.clear();
    }

    @Test
    @DisplayName("Default deny set covers recursion guards and memory writers; no shell/IM names")
    void defaultDenyListShape() {
        Set<String> defaults = DelegateAgentTool.DEFAULT_CHILD_DENIED_TOOLS;
        // Recursion guards.
        assertThat(defaults).contains("delegateToAgent", "delegateParallel", "listAvailableAgents");
        // Memory writers (canonical Spring AI tool method names — do not include
        // any speculative names that would silently no-op).
        assertThat(defaults).contains("remember", "remember_structured", "forget_structured");
        // Shell stays out by design — see comment on DEFAULT_CHILD_DENIED_TOOLS.
        assertThat(defaults).doesNotContain("execute_shell_command");
    }

    @Test
    @DisplayName("Operator-supplied additions merge into the effective deny list")
    void additionalDeniedToolsMergeWithDefaults() throws Exception {
        injectAdditional(List.of("custom_tool", "another_tool"));

        Set<String> effective = tool.deniedToolsForChild();

        assertThat(effective).containsAll(DelegateAgentTool.DEFAULT_CHILD_DENIED_TOOLS);
        assertThat(effective).contains("custom_tool", "another_tool");
        // Defaults stay untouched — we returned a fresh merged set.
        assertThat(DelegateAgentTool.DEFAULT_CHILD_DENIED_TOOLS).doesNotContain("custom_tool");
    }

    @Test
    @DisplayName("Empty additional list returns the default set unchanged")
    void emptyAdditionalReturnsDefault() throws Exception {
        injectAdditional(List.of());
        assertThat(tool.deniedToolsForChild()).isEqualTo(DelegateAgentTool.DEFAULT_CHILD_DENIED_TOOLS);

        injectAdditional(null);
        assertThat(tool.deniedToolsForChild()).isEqualTo(DelegateAgentTool.DEFAULT_CHILD_DENIED_TOOLS);
    }

    @Test
    @DisplayName("Blank entries in additional list are ignored")
    void blankEntriesIgnored() throws Exception {
        injectAdditional(List.of("", "   ", "real_tool"));
        Set<String> effective = tool.deniedToolsForChild();
        assertThat(effective).contains("real_tool");
        assertThat(effective).doesNotContain("");
        assertThat(effective).doesNotContain("   ");
    }

    @Test
    @DisplayName("delegateToAgent short-circuits when the parent conversation is spawn-paused")
    void delegateToAgentRespectsSpawnPause() {
        // Set up a real agent the lookup will return so we'd otherwise fall
        // through to child execution. The short-circuit must beat that.
        AgentEntity agent = new AgentEntity();
        agent.setId(1L);
        agent.setName("Worker");
        agent.setEnabled(true);
        agent.setWorkspaceId(1L);
        when(agentMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(agent);

        ToolExecutionContext.set("parent-conv", "alice");
        registry.setSpawnPaused("parent-conv", true);

        String result = tool.delegateToAgent("Worker", "do thing", null, null);

        assertThat(result).contains("Spawning paused");
        // No child registered when the spawn is rejected.
        assertThat(registry.snapshot("parent-conv")).isEmpty();
    }

    @Test
    @DisplayName("delegateParallel short-circuits when the parent conversation is spawn-paused")
    void delegateParallelRespectsSpawnPause() {
        ToolExecutionContext.set("parent-conv", "alice");
        registry.setSpawnPaused("parent-conv", true);

        String result = tool.delegateParallel(
                "[{\"agentName\":\"Worker\",\"task\":\"task1\"}]", null);

        assertThat(result).contains("Spawning paused");
        assertThat(registry.snapshot("parent-conv")).isEmpty();
    }

    /**
     * Inject the {@code additionalDeniedTools} field bypassing Spring's
     * {@code @Value} binding so the test can drive merge logic deterministically.
     */
    private void injectAdditional(List<String> values) throws Exception {
        Field f = DelegateAgentTool.class.getDeclaredField("additionalDeniedTools");
        f.setAccessible(true);
        f.set(tool, values);
    }
}
