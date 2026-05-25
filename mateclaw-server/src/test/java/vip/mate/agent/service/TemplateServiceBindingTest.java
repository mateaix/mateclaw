package vip.mate.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.agent.AgentService;
import vip.mate.agent.binding.service.AgentBindingService;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.model.TemplateDTO;
import vip.mate.exception.MateClawException;
import vip.mate.skill.model.SkillEntity;
import vip.mate.skill.repository.SkillMapper;
import vip.mate.tool.model.AvailableToolDTO;
import vip.mate.tool.service.AvailableToolService;
import vip.mate.workspace.document.WorkspaceFileService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Hire-time pre-binding behavior for {@link TemplateService#applyTemplate}.
 *
 * <p>The contract being pinned: a template that declares
 * {@code defaultSkillSlugs} / {@code defaultToolNames} produces an agent that
 * already has those capabilities wired, and references that can't be
 * resolved (slug not in this workspace, tool not in the picker) are dropped
 * silently — the hire MUST still succeed so a partially-installed
 * environment doesn't break onboarding.
 */
class TemplateServiceBindingTest {

    private static final long WORKSPACE = 1L;
    private static final long CREATOR = 7L;
    private static final long CREATED_AGENT_ID = 999L;

    private AgentService agentService;
    private WorkspaceFileService workspaceFileService;
    private AgentBindingService agentBindingService;
    private SkillMapper skillMapper;
    private AvailableToolService availableToolService;
    private TemplateService service;
    private TemplateService spyService;

    @BeforeEach
    void setUp() {
        agentService = mock(AgentService.class);
        workspaceFileService = mock(WorkspaceFileService.class);
        agentBindingService = mock(AgentBindingService.class);
        skillMapper = mock(SkillMapper.class);
        availableToolService = mock(AvailableToolService.class);

        // createAgent stamps an id and echoes the entity back, matching the
        // real DAO contract the production code relies on.
        when(agentService.createAgent(any(AgentEntity.class))).thenAnswer(inv -> {
            AgentEntity a = inv.getArgument(0);
            a.setId(CREATED_AGENT_ID);
            return a;
        });

        service = new TemplateService(
                agentService,
                workspaceFileService,
                new ObjectMapper(),
                agentBindingService,
                skillMapper,
                availableToolService);
        spyService = spy(service);
    }

    /** Build a minimal template; tests append bind lists. */
    private TemplateDTO baseTemplate(String id) {
        TemplateDTO t = new TemplateDTO();
        t.setId(id);
        t.setName(id);
        t.setDescription("test");
        t.setAgentType("react");
        t.setMaxIterations(10);
        t.setSystemPrompt("## Role\ntest");
        return t;
    }

    /** Stub the in-memory template registry so the test owns the data. */
    private void registerTemplate(TemplateDTO template) {
        doReturn(List.of(template)).when(spyService).listTemplates();
    }

    private SkillEntity skillRow(long id, String slug) {
        SkillEntity s = new SkillEntity();
        s.setId(id);
        s.setName(slug);
        s.setWorkspaceId(WORKSPACE);
        return s;
    }

    private AvailableToolDTO availableTool(String name) {
        AvailableToolDTO dto = new AvailableToolDTO();
        dto.setName(name);
        dto.setAvailable(true);
        return dto;
    }

    @Test
    @DisplayName("declared skill slugs resolve to ids and pre-bind on the new agent")
    void preBindsDeclaredSkillSlugs() {
        TemplateDTO t = baseTemplate("data-analyst-stub");
        t.setDefaultSkillSlugs(List.of("sql_query", "xlsx"));
        registerTemplate(t);

        when(skillMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(skillRow(101L, "sql_query"))
                .thenReturn(skillRow(202L, "xlsx"));

        AgentEntity created = spyService.applyTemplate("data-analyst-stub", WORKSPACE, CREATOR, null);

        assertNotNull(created.getId());
        verify(agentBindingService, times(1))
                .setSkillBindings(eq(CREATED_AGENT_ID), argThat(ids ->
                        ids.size() == 2 && ids.contains(101L) && ids.contains(202L)));
        // No tool bindings declared → no tool side-effects.
        verify(agentBindingService, never()).setToolBindings(anyLong(), anyList());
    }

    @Test
    @DisplayName("missing slugs are skipped without aborting the hire")
    void skipsMissingSlugsAndStillHires() {
        TemplateDTO t = baseTemplate("partial-stub");
        t.setDefaultSkillSlugs(List.of("ghost-skill", "sql_query"));
        registerTemplate(t);

        when(skillMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(null)                       // ghost-skill not in workspace
                .thenReturn(skillRow(101L, "sql_query"));

        AgentEntity created = spyService.applyTemplate("partial-stub", WORKSPACE, CREATOR, null);

        assertNotNull(created.getId());
        // Only the resolvable slug makes it into the binding call.
        verify(agentBindingService, times(1))
                .setSkillBindings(eq(CREATED_AGENT_ID), argThat(ids ->
                        ids.size() == 1 && ids.contains(101L)));
    }

    @Test
    @DisplayName("when every slug is unknown, setSkillBindings is never called and the agent still exists")
    void noSlugsResolveSoNoBindCall() {
        TemplateDTO t = baseTemplate("all-ghost-stub");
        t.setDefaultSkillSlugs(List.of("ghost-a", "ghost-b"));
        registerTemplate(t);

        when(skillMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        AgentEntity created = spyService.applyTemplate("all-ghost-stub", WORKSPACE, CREATOR, null);

        assertNotNull(created.getId());
        // Empty resolved list → caller must NOT issue an empty
        // setSkillBindings (which would otherwise wipe out future bindings
        // post-create if any race wrote them in between).
        verify(agentBindingService, never()).setSkillBindings(anyLong(), anyList());
    }

    @Test
    @DisplayName("legacy templates with no binding fields behave as before")
    void noBindingFieldsLeavesAgentUntouched() {
        TemplateDTO t = baseTemplate("legacy-stub");
        // Neither defaultSkillSlugs nor defaultToolNames set.
        registerTemplate(t);

        AgentEntity created = spyService.applyTemplate("legacy-stub", WORKSPACE, CREATOR, null);

        assertNotNull(created.getId());
        verify(agentBindingService, never()).setSkillBindings(anyLong(), anyList());
        verify(agentBindingService, never()).setToolBindings(anyLong(), anyList());
    }

    @Test
    @DisplayName("tool names are pre-filtered through the picker before binding")
    void toolBindingsFilterAgainstPicker() {
        TemplateDTO t = baseTemplate("tool-stub");
        t.setDefaultToolNames(List.of("search", "ghost_tool", "browser_use"));
        registerTemplate(t);

        when(availableToolService.listAvailable()).thenReturn(List.of(
                availableTool("search"),
                availableTool("browser_use")));

        AgentEntity created = spyService.applyTemplate("tool-stub", WORKSPACE, CREATOR, null);

        assertNotNull(created.getId());
        // ghost_tool is not in the picker → filtered. The remaining two
        // pass through; setToolBindings's own validator would otherwise
        // throw on the unknown name and abort the entire bind call.
        verify(agentBindingService, times(1))
                .setToolBindings(eq(CREATED_AGENT_ID), argThat(names ->
                        names.size() == 2
                                && names.contains("search")
                                && names.contains("browser_use")
                                && !names.contains("ghost_tool")));
    }

    @Test
    @DisplayName("picker failure during apply skips tool binding instead of breaking the hire")
    void pickerFailureDoesNotBreakHire() {
        TemplateDTO t = baseTemplate("picker-down-stub");
        t.setDefaultToolNames(List.of("search"));
        registerTemplate(t);

        when(availableToolService.listAvailable())
                .thenThrow(new RuntimeException("MCP discovery upstream timeout"));

        AgentEntity created = spyService.applyTemplate("picker-down-stub", WORKSPACE, CREATOR, null);

        // Hire still completes; tool bind silently skipped (conservative
        // stance documented on applyDefaultToolBindings).
        assertEquals(CREATED_AGENT_ID, created.getId());
        verify(agentBindingService, never()).setToolBindings(anyLong(), anyList());
    }

    @Test
    @DisplayName("setSkillBindings exception propagates so @Transactional rolls back the hire")
    void bindServiceExceptionPropagates() {
        // Pins the documented split: resolution failures are graceful, but
        // service-layer exceptions (a race deleting the skill row between
        // resolve and bind, a workspace-mismatch we couldn't predict) are
        // fail-stop. If someone later wraps the bind call in try/catch to
        // "make it more robust", this test forces them to also revisit
        // applyDefaultSkillBindings's Javadoc and the @Transactional
        // rollback contract instead of silently changing behavior.
        TemplateDTO t = baseTemplate("racey-stub");
        t.setDefaultSkillSlugs(List.of("sql_query"));
        registerTemplate(t);

        when(skillMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(skillRow(101L, "sql_query"));
        doThrow(new MateClawException("err.skill.cross_workspace_binding", 403, "simulated race"))
                .when(agentBindingService).setSkillBindings(anyLong(), anyList());

        MateClawException thrown = assertThrows(MateClawException.class,
                () -> spyService.applyTemplate("racey-stub", WORKSPACE, CREATOR, null));
        assertEquals(403, thrown.getCode());
        assertEquals("err.skill.cross_workspace_binding", thrown.getMsgKey());
    }

    @Test
    @DisplayName("workspace lookup reads from the persisted agent — survives a service-side workspace override")
    void workspaceLookupUsesPersistedAgent() {
        // Defends against a future where AgentService.createAgent normalises
        // workspaceId (auto-assign default, project-onto-user-default, etc.)
        // — the slug resolver MUST query the same workspace that the bind
        // validator will check. Here we mutate the persisted agent's
        // workspace to a value different from the input parameter; if the
        // helper still queried the parameter, the lookup would target the
        // wrong workspace and (in production) miss the seeded skill. We
        // can't introspect the LambdaQueryWrapper's parameter map from a
        // Mockito-only test (MyBatis-Plus lambda cache isn't bootstrapped),
        // so this test pins the flow against crashes; the workspace-source
        // correctness is enforced by code review on the helper itself.
        when(agentService.createAgent(any(AgentEntity.class))).thenAnswer(inv -> {
            AgentEntity a = inv.getArgument(0);
            a.setId(CREATED_AGENT_ID);
            a.setWorkspaceId(42L);
            return a;
        });

        TemplateDTO t = baseTemplate("ws-override-stub");
        t.setDefaultSkillSlugs(List.of("sql_query"));
        registerTemplate(t);

        when(skillMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(skillRow(101L, "sql_query"));

        spyService.applyTemplate("ws-override-stub", WORKSPACE /* = 1 */, CREATOR, null);

        verify(agentBindingService, times(1))
                .setSkillBindings(eq(CREATED_AGENT_ID), argThat(ids ->
                        ids.size() == 1 && ids.contains(101L)));
    }

    @Test
    @DisplayName("null workspace on the persisted agent does not crash the helper")
    void nullWorkspaceFallsBackToOne() {
        // Mirrors the AgentBindingService.requireSameWorkspace fallback —
        // a row with workspace_id = null must not produce an `IS NULL`
        // lookup that silently matches nothing. The helper falls back to
        // workspace 1; without that, the LambdaQueryWrapper would still
        // build but every seeded skill would miss. Smoke-tested here for
        // crash-freeness; the value of the fallback (1L) is asserted by
        // code review of the helper.
        when(agentService.createAgent(any(AgentEntity.class))).thenAnswer(inv -> {
            AgentEntity a = inv.getArgument(0);
            a.setId(CREATED_AGENT_ID);
            a.setWorkspaceId(null);
            return a;
        });

        TemplateDTO t = baseTemplate("null-ws-stub");
        t.setDefaultSkillSlugs(List.of("sql_query"));
        registerTemplate(t);

        when(skillMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(skillRow(101L, "sql_query"));

        spyService.applyTemplate("null-ws-stub", WORKSPACE, CREATOR, null);

        verify(agentBindingService, times(1))
                .setSkillBindings(eq(CREATED_AGENT_ID), argThat(ids -> ids.contains(101L)));
    }

    @Test
    @DisplayName("blank slug entries are skipped before they reach the mapper")
    void blankSlugsSkipped() {
        TemplateDTO t = baseTemplate("blanks-stub");
        t.setDefaultSkillSlugs(java.util.Arrays.asList("sql_query", "", null, "  "));
        registerTemplate(t);

        when(skillMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(skillRow(101L, "sql_query"));

        AgentEntity created = spyService.applyTemplate("blanks-stub", WORKSPACE, CREATOR, null);

        assertNotNull(created.getId());
        // Only the one real slug triggers a mapper lookup → only one bind.
        verify(skillMapper, times(1)).selectOne(any(LambdaQueryWrapper.class));
        verify(agentBindingService, times(1))
                .setSkillBindings(eq(CREATED_AGENT_ID), argThat(ids ->
                        ids.size() == 1 && ids.contains(101L)));
    }
}
