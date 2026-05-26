package vip.mate.agent.binding;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import vip.mate.MateClawApplication;
import vip.mate.agent.binding.model.AgentToolBinding;
import vip.mate.agent.binding.service.AgentBindingService;
import vip.mate.exception.MateClawException;
import vip.mate.tool.model.AvailableToolDTO;
import vip.mate.tool.service.AvailableToolService;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 覆盖 issue #8 的重绑定 bug 回归：在去掉 @TableLogic 之前，
 * bind → unbind → rebind 会因为 uk_agent_tool / uk_agent_skill 唯一索引
 * 与软删除并存而抛 DuplicateKeyException。本测试断言修复后各条路径都成功，
 * 同时断言合法的唯一约束仍被保留（不能让修 bug 顺带破坏唯一性）。
 */
@SpringBootTest(
        classes = MateClawApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:binding_test_${random.uuid};MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.ai.dashscope.api-key=test-key",
        "spring.main.web-application-type=none"
})
class AgentBindingServiceTest {

    private static final AtomicLong AGENT_ID_SEQ = new AtomicLong(9_000_000L);

    @Autowired
    private AgentBindingService bindingService;

    @Autowired
    private AvailableToolService availableToolService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long agentId;

    @BeforeEach
    void setUp() {
        // Each test gets its own agent id so concurrent runs don't clash.
        agentId = AGENT_ID_SEQ.getAndIncrement();
        // Seed a real mate_agent row so AgentBindingService.requireSameWorkspace
        // can resolve the agent's workspace during bindSkill/setSkillBindings.
        // Tool-binding tests don't strictly need it but seeding is cheap and
        // keeps every code path realistic.
        seedAgent(agentId);
    }

    private void seedAgent(long id) {
        jdbcTemplate.update(
                "MERGE INTO mate_agent (id, name, agent_type, system_prompt, max_iterations, enabled, " +
                        "workspace_id, create_time, update_time, deleted) " +
                        "KEY(id) VALUES (?, ?, 'react', '', 10, TRUE, 1, " +
                        "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)",
                id, "binding-test-agent-" + id);
    }

    private void seedSkill(long id) {
        seedSkill(id, 1L);
    }

    /**
     * Skill seeder with explicit workspace_id so the cross-workspace
     * rejection path can be exercised.
     */
    private void seedSkill(long id, long workspaceId) {
        jdbcTemplate.update(
                "MERGE INTO mate_skill (id, name, skill_type, version, enabled, builtin, " +
                        "workspace_id, create_time, update_time, deleted) " +
                        "KEY(id) VALUES (?, ?, 'dynamic', '1.0.0', TRUE, FALSE, ?, " +
                        "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)",
                id, "binding-test-skill-" + id, workspaceId);
    }

    /**
     * ACP endpoint seeder. The bridge synthesizes a virtual skill with id
     * {@code AcpSkillBridge.VIRTUAL_ID_BASE + endpointId} and inherits
     * {@code workspaceId} from the row, so this is the lever for testing
     * the bridge-backed workspace check in {@code requireSameWorkspace}.
     */
    private void seedAcpEndpoint(long endpointId, long workspaceId) {
        jdbcTemplate.update(
                "MERGE INTO mate_acp_endpoint (id, name, command, builtin, trusted, enabled, " +
                        "workspace_id, create_time, update_time, deleted) " +
                        "KEY(id) VALUES (?, ?, 'echo', FALSE, TRUE, TRUE, ?, " +
                        "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)",
                endpointId, "binding-test-acp-" + endpointId, workspaceId);
    }

    @Test
    @DisplayName("bindTool → unbindTool → bindTool 同一 (agent, tool) 不抛异常")
    void rebindToolAfterUnbind() {
        bindingService.bindTool(agentId, "echo");
        bindingService.unbindTool(agentId, "echo");
        assertDoesNotThrow(() -> bindingService.bindTool(agentId, "echo"));

        Set<String> names = bindingService.getBoundToolNames(agentId);
        assertNotNull(names);
        assertTrue(names.contains("echo"));
    }

    @Test
    @DisplayName("bindSkill → unbindSkill → bindSkill 同一 (agent, skill) 不抛异常")
    void rebindSkillAfterUnbind() {
        long skillId = 7_777_001L;
        seedSkill(skillId);
        bindingService.bindSkill(agentId, skillId);
        bindingService.unbindSkill(agentId, skillId);
        assertDoesNotThrow(() -> bindingService.bindSkill(agentId, skillId));

        Set<Long> ids = bindingService.getBoundSkillIds(agentId);
        assertNotNull(ids);
        assertTrue(ids.contains(skillId));
    }

    @Test
    @DisplayName("setToolBindings 连续调用两次相同列表不抛异常，状态收敛")
    void setToolBindingsIsIdempotent() {
        // setToolBindings now refuses unknown tool names (so an API caller
        // can't write a binding the runtime won't be able to resolve).
        // Seed two real rows in mate_tool first so the validator considers
        // the names bindable; the test's intent — idempotent persistence —
        // is unchanged.
        seedBuiltinTool("tool_a");
        seedBuiltinTool("tool_b");
        List<String> desired = List.of("tool_a", "tool_b");
        bindingService.setToolBindings(agentId, desired);
        assertDoesNotThrow(() -> bindingService.setToolBindings(agentId, desired));

        Set<String> names = bindingService.getBoundToolNames(agentId);
        assertNotNull(names);
        assertEquals(2, names.size());
        assertTrue(names.containsAll(desired));
    }

    private void seedBuiltinTool(String name) {
        jdbcTemplate.update(
                "MERGE INTO mate_tool (id, name, display_name, description, tool_type, bean_name, icon, enabled, builtin, create_time, update_time, deleted) " +
                        "KEY(name) VALUES (?, ?, ?, ?, 'builtin', ?, '🔧', TRUE, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)",
                System.nanoTime(), name, name, "test fixture", name);
    }

    @Test
    @DisplayName("setSkillBindings 连续调用两次相同列表不抛异常，状态收敛")
    void setSkillBindingsIsIdempotent() {
        List<Long> desired = List.of(7_777_101L, 7_777_102L);
        desired.forEach(this::seedSkill);
        bindingService.setSkillBindings(agentId, desired);
        assertDoesNotThrow(() -> bindingService.setSkillBindings(agentId, desired));

        Set<Long> ids = bindingService.getBoundSkillIds(agentId);
        assertNotNull(ids);
        assertEquals(2, ids.size());
        assertTrue(ids.containsAll(desired));
    }

    @Test
    @DisplayName("唯一性回归：同一 (agent, tool) 直接 INSERT 第二行仍被唯一索引拦截")
    void uniqueIndexStillEnforcedForTool() {
        bindingService.bindTool(agentId, "unique_probe");

        // 绕过 service，直接 INSERT 第二行，断言 DB 层唯一约束仍生效
        assertThrows(DuplicateKeyException.class, () ->
                jdbcTemplate.update(
                        "INSERT INTO mate_agent_tool " +
                                "(id, agent_id, tool_name, enabled, create_time, update_time, deleted) " +
                                "VALUES (?, ?, ?, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)",
                        System.nanoTime(), agentId, "unique_probe"
                )
        );
    }

    @Test
    @DisplayName("唯一性回归：同一 (agent, skill) 直接 INSERT 第二行仍被唯一索引拦截")
    void uniqueIndexStillEnforcedForSkill() {
        long skillId = 7_777_201L;
        seedSkill(skillId);
        bindingService.bindSkill(agentId, skillId);

        assertThrows(DuplicateKeyException.class, () ->
                jdbcTemplate.update(
                        "INSERT INTO mate_agent_skill " +
                                "(id, agent_id, skill_id, enabled, create_time, update_time, deleted) " +
                                "VALUES (?, ?, ?, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)",
                        System.nanoTime(), agentId, skillId
                )
        );
    }

    @Test
    @DisplayName("bindSkill 拒绝跨 workspace 的真实 skill（防止 tenancy 越界）")
    void bindSkillRejectsCrossWorkspaceRealSkill() {
        // Agent lives in workspace 1 (set up in @BeforeEach). Seed a skill
        // in workspace 2 — the new requireSameWorkspace check should refuse.
        long otherWorkspaceSkillId = 7_777_301L;
        seedSkill(otherWorkspaceSkillId, 2L);

        MateClawException ex = assertThrows(MateClawException.class,
                () -> bindingService.bindSkill(agentId, otherWorkspaceSkillId));
        assertEquals(403, ex.getCode(), "应返回 403 业务码（跨 workspace 越界）");
        assertEquals("err.skill.cross_workspace_binding", ex.getMsgKey(),
                "应使用专用 i18n key，前端可精确分支");

        // No row should have been written before the check failed.
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mate_agent_skill WHERE agent_id = ? AND skill_id = ?",
                Integer.class, agentId, otherWorkspaceSkillId);
        assertNotNull(count);
        assertEquals(0, count, "拒绝时不能写入绑定行");
    }

    @Test
    @DisplayName("bindSkill 允许跨 workspace 的 builtin skill（builtin 为全局能力，不做 tenancy 校验）")
    void bindSkillAllowsBuiltinSkillCrossWorkspace() {
        // Builtin skills are seeded once into the default workspace but are
        // global — an agent in any workspace must be able to bind them. Seed
        // a builtin row whose workspace_id deliberately differs from the
        // agent's (=1) to prove the builtin exemption, not a workspace match,
        // is what lets the binding through.
        long builtinSkillId = 7_777_350L;
        jdbcTemplate.update(
                "MERGE INTO mate_skill (id, name, skill_type, version, enabled, builtin, " +
                        "workspace_id, create_time, update_time, deleted) " +
                        "KEY(id) VALUES (?, ?, 'builtin', '1.0.0', TRUE, TRUE, 2, " +
                        "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)",
                builtinSkillId, "binding-test-builtin-" + builtinSkillId);

        assertDoesNotThrow(() -> bindingService.bindSkill(agentId, builtinSkillId));

        Set<Long> ids = bindingService.getBoundSkillIds(agentId);
        assertNotNull(ids);
        assertTrue(ids.contains(builtinSkillId), "builtin skill 应当可跨 workspace 绑定");
    }

    @Test
    @DisplayName("bindSkill 允许 MCP 虚拟 skill（McpServerEntity 无 workspace，全局共享）")
    void bindSkillAllowsVirtualMcpSkill() {
        // Virtual MCP id range starts at McpSkillBridge.VIRTUAL_ID_BASE (9e18).
        // No mate_skill or mate_mcp_server seeding needed — the bridge is
        // bypassed entirely for MCP because there's no workspace to compare.
        long virtualMcpId = vip.mate.skill.mcp.McpSkillBridge.VIRTUAL_ID_BASE + 42L;
        assertDoesNotThrow(() -> bindingService.bindSkill(agentId, virtualMcpId));

        Set<Long> ids = bindingService.getBoundSkillIds(agentId);
        assertNotNull(ids);
        assertTrue(ids.contains(virtualMcpId), "MCP virtual binding 应当落到 mate_agent_skill");
    }

    @Test
    @DisplayName("bindSkill 允许同 workspace 的 ACP 虚拟 skill（走 AcpSkillBridge 解析 workspace）")
    void bindSkillAllowsVirtualAcpSkillSameWorkspace() {
        long endpointId = 4_242_001L;
        seedAcpEndpoint(endpointId, 1L); // matches the agent's workspace
        long virtualAcpId = vip.mate.skill.acp.AcpSkillBridge.VIRTUAL_ID_BASE + endpointId;

        assertDoesNotThrow(() -> bindingService.bindSkill(agentId, virtualAcpId));

        Set<Long> ids = bindingService.getBoundSkillIds(agentId);
        assertNotNull(ids);
        assertTrue(ids.contains(virtualAcpId), "ACP virtual binding 应当落到 mate_agent_skill");
    }

    @Test
    @DisplayName("bindSkill 拒绝跨 workspace 的 ACP 虚拟 skill（endpoint 的 workspace 与 agent 不一致）")
    void bindSkillRejectsVirtualAcpSkillCrossWorkspace() {
        long endpointId = 4_242_002L;
        seedAcpEndpoint(endpointId, 2L); // different workspace from the agent (=1)
        long virtualAcpId = vip.mate.skill.acp.AcpSkillBridge.VIRTUAL_ID_BASE + endpointId;

        MateClawException ex = assertThrows(MateClawException.class,
                () -> bindingService.bindSkill(agentId, virtualAcpId));
        assertEquals(403, ex.getCode());
        assertEquals("err.skill.cross_workspace_binding", ex.getMsgKey());
    }

    @Test
    @DisplayName("setSkillBindings 在批量中先做完所有校验，再删旧绑定（半成品保护）")
    void setSkillBindingsValidatesBeforeMutating() {
        // Seed one good skill (ws=1, same as agent) so getBoundSkillIds
        // is non-empty before the failing batch. Then call setSkillBindings
        // with one good + one cross-workspace id — the whole batch must be
        // refused and the original binding must survive untouched.
        long goodSkill = 7_777_401L;
        seedSkill(goodSkill, 1L);
        bindingService.bindSkill(agentId, goodSkill);

        long badSkill = 7_777_402L;
        seedSkill(badSkill, 2L);

        assertThrows(MateClawException.class,
                () -> bindingService.setSkillBindings(agentId, List.of(goodSkill, badSkill)));

        // The pre-existing binding to goodSkill must still be there —
        // validation should have failed before the DELETE ran.
        Set<Long> remaining = bindingService.getBoundSkillIds(agentId);
        assertNotNull(remaining);
        assertTrue(remaining.contains(goodSkill),
                "validation 必须在 delete 旧绑定之前完成，否则会留下空绑定状态");
    }

    /**
     * Seed a connected MCP server with one cached tool so
     * {@link vip.mate.tool.service.AvailableToolService#listAvailable()}
     * returns at least one bindable MCP row.
     */
    private void seedMcpServerWithOneTool(long id, String serverName, String rawToolName) {
        String toolsCacheJson = "[{\"name\":\"" + rawToolName + "\",\"description\":\"fixture\"}]";
        jdbcTemplate.update(
                "MERGE INTO mate_mcp_server (id, name, description, transport, enabled, " +
                        "connect_timeout_seconds, read_timeout_seconds, last_status, tool_count, " +
                        "builtin, tools_cache_json, create_time, update_time, deleted) " +
                        "KEY(id) VALUES (?, ?, '', 'stdio', TRUE, 30, 30, 'connected', 1, FALSE, ?, " +
                        "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)",
                id, serverName, toolsCacheJson);
    }

    @Test
    @DisplayName("Issue #108: 绑定任意 builtin tool 后，enabled MCP 工具仍自动出现在 effective allowlist")
    void mcpToolsAutoIncludedWhenAnyBindingExists() {
        // Reproduce the user-reported scenario: agent has one built-in tool
        // bound (e.g. by template), no MCP tools ticked. Before the fix this
        // returned a whitelist that excluded every MCP tool; after the fix
        // MCP tools auto-join the allowlist.
        seedBuiltinTool("builtin_probe");
        seedMcpServerWithOneTool(8_888_001L, "issue108-server", "search_web");
        bindingService.setToolBindings(agentId, List.of("builtin_probe"));

        Set<String> effective = bindingService.getEffectiveToolNames(agentId);
        assertNotNull(effective, "binding 非空时应返回 allowlist（非 null）");
        assertTrue(effective.contains("builtin_probe"), "用户显式勾选的工具必须在 allowlist 中");
        boolean hasMcpEntry = effective.stream().anyMatch(n -> n != null && n.startsWith("mcp_"));
        assertTrue(hasMcpEntry,
                "enabled MCP server 的工具必须自动并入 allowlist；缺失会让用户在 chat 时只见到 built-in 工具，"
                        + "即 issue #108 描述的现象。实际 allowlist: " + effective);
    }

    @Test
    @DisplayName("Issue #108: agent 完全没绑定时 effective allowlist 返回 null（不要意外改成 strict）")
    void noBindingsStillReturnsNull() {
        // The auto-union must not flip the three-state contract: an agent
        // with zero bindings still means "no agent-level restriction".
        seedMcpServerWithOneTool(8_888_002L, "issue108-no-binding-server", "search_web");

        Set<String> effective = bindingService.getEffectiveToolNames(agentId);
        assertNull(effective, "完全没有 skill / tool 绑定时必须返回 null（= 不过滤），"
                + "否则 AgentToolSet.withAllowedToolsOnly 会变成空集禁掉所有工具");
    }

    @Test
    @DisplayName("Issue #117: agent 显式勾选某个 MCP 工具后，只有该工具进入 allowlist，其它 MCP 工具不再自动并入")
    void mcpToolsScopedWhenAgentPicksSpecificMcpTool() {
        // Enterprise scenario: a role should be limited to a fixed subset
        // of MCP tools. Two enabled MCP servers exist; the operator ticks
        // only server A's tool. Server B's tool must NOT leak into the
        // allowlist just because its server is enabled at the system level.
        seedMcpServerWithOneTool(8_888_101L, "issue117-server-a", "alpha_probe");
        seedMcpServerWithOneTool(8_888_102L, "issue117-server-b", "beta_probe");

        String mcpA = mcpToolNameForServer(8_888_101L);
        String mcpB = mcpToolNameForServer(8_888_102L);
        assertNotNull(mcpA, "server A 的 MCP 工具应出现在 picker 中");
        assertNotNull(mcpB, "server B 的 MCP 工具应出现在 picker 中");

        bindingService.setToolBindings(agentId, List.of(mcpA));

        Set<String> effective = bindingService.getEffectiveToolNames(agentId);
        assertNotNull(effective, "binding 非空时应返回 allowlist（非 null）");
        assertTrue(effective.contains(mcpA), "显式勾选的 MCP 工具必须在 allowlist 中");
        assertFalse(effective.contains(mcpB),
                "未勾选的其它 MCP 工具不得自动并入 —— 这正是 issue #117 要求的按岗位限定 MCP 范围。"
                        + "实际 allowlist: " + effective);
    }

    /** Picker name the UI would save for the (only) MCP tool of {@code serverId}. */
    private String mcpToolNameForServer(long serverId) {
        return availableToolService.listAvailable().stream()
                .filter(t -> "mcp".equals(t.getSource()))
                .filter(t -> t.getProviderId() != null && serverId == t.getProviderId())
                .map(AvailableToolDTO::getName)
                .findFirst()
                .orElse(null);
    }

    @Test
    @DisplayName("Issue #143: 绑定任意工具后，wiki 知识库工具仍留在 effective allowlist（可读写知识库）")
    void wikiToolsSurviveSkillBindingAllowlist() {
        // Reproduce issue #143: once an agent has any binding, the effective
        // allowlist turns on. Wiki tools live on the WikiTool bean and are
        // never declared by a skill manifest, so before the fix they were
        // filtered out — the agent could chat but lost its KB read/write
        // tools and reported "no permission" when asked to save a result.
        seedBuiltinTool("builtin_probe");
        bindingService.setToolBindings(agentId, List.of("builtin_probe"));

        Set<String> effective = bindingService.getEffectiveToolNames(agentId);
        assertNotNull(effective, "binding 非空时应返回 allowlist（非 null）");
        assertTrue(effective.contains("wiki_create_page"),
                "wiki_create_page 必须留在 allowlist —— 否则 AI 无法把结果写入知识库（issue #143）。"
                        + "实际 allowlist: " + effective);
        assertTrue(effective.contains("wiki_read_page"),
                "wiki_read_page 必须留在 allowlist —— 否则 agent 无法读取自己的知识库。"
                        + "实际 allowlist: " + effective);
    }

    @Test
    @DisplayName("unbindTool 后 DB 里真的没行（物理 delete，不是软删留 deleted=1）")
    void unbindPhysicallyRemovesRow() {
        bindingService.bindTool(agentId, "physical_check");
        bindingService.unbindTool(agentId, "physical_check");

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mate_agent_tool WHERE agent_id = ? AND tool_name = ?",
                Integer.class, agentId, "physical_check"
        );
        assertNotNull(count);
        assertEquals(0, count, "unbind 应该物理删除，而不是软删（软删会留 deleted=1 行，占用唯一索引槽位导致 rebind 失败）");
    }

    // ==================== V126 binding-mode flags (issue #184) ====================

    /**
     * Flip the {@code skills_disabled} column on the seeded agent row.
     * Tests need a direct lever because {@link AgentBindingService} only
     * exposes the auto-clear side; setting the flag is the controller's job.
     */
    private void setSkillsDisabledFlag(boolean value) {
        jdbcTemplate.update(
                "UPDATE mate_agent SET skills_disabled = ? WHERE id = ?",
                value, agentId);
    }

    /** Mirror of {@link #setSkillsDisabledFlag} for the tools toggle. */
    private void setToolsDisabledFlag(boolean value) {
        jdbcTemplate.update(
                "UPDATE mate_agent SET tools_disabled = ? WHERE id = ?",
                value, agentId);
    }

    /** Boolean column readback so the auto-clear assertions don't lie. */
    private boolean readSkillsDisabledFlag() {
        Boolean v = jdbcTemplate.queryForObject(
                "SELECT skills_disabled FROM mate_agent WHERE id = ?",
                Boolean.class, agentId);
        return Boolean.TRUE.equals(v);
    }

    private boolean readToolsDisabledFlag() {
        Boolean v = jdbcTemplate.queryForObject(
                "SELECT tools_disabled FROM mate_agent WHERE id = ?",
                Boolean.class, agentId);
        return Boolean.TRUE.equals(v);
    }

    @Test
    @DisplayName("issue #184: skills_disabled=true → getBoundSkillIds 返回 emptySet（不是 null）")
    void getBoundSkillIdsReturnsEmptyWhenSkillsDisabled() {
        // No binding rows at all + flag on. Pre-V126 contract returned null
        // (= inherit global default); the new flag flips the read to an
        // explicit "no skills" so SKILL.md catalog injection stays off.
        setSkillsDisabledFlag(true);

        Set<Long> result = bindingService.getBoundSkillIds(agentId);
        assertNotNull(result, "skills_disabled=true 时绝不能返回 null —— 否则下游会把它当作 'inherit global default'");
        assertTrue(result.isEmpty(), "应当是显式的空 set");
    }

    @Test
    @DisplayName("issue #184: tools_disabled=true → getBoundToolNames 返回 emptySet（不是 null）")
    void getBoundToolNamesReturnsEmptyWhenToolsDisabled() {
        setToolsDisabledFlag(true);

        Set<String> result = bindingService.getBoundToolNames(agentId);
        assertNotNull(result, "tools_disabled=true 时绝不能返回 null");
        assertTrue(result.isEmpty(), "应当是显式的空 set");
    }

    @Test
    @DisplayName("issue #184 matrix (T,F,*,0): skillsDisabled 但无 tool 绑定 → effective 返回 null（工具继承全局默认）")
    void skillsDisabledNoToolBindingsStillInheritsDefaultTools() {
        // This is the critical case the design review caught: silently
        // returning {SYSTEM + MCP} here would strip every non-MCP global
        // built-in tool just because the user said "no skills". The fix
        // returns null so AgentToolSet.withAllowedToolsOnly(null) → no
        // restriction → global default tools flow through.
        setSkillsDisabledFlag(true);

        Set<String> effective = bindingService.getEffectiveToolNames(agentId);
        assertNull(effective,
                "skillsDisabled=true 但用户没主动限制工具时，effective 必须返回 null —— "
                        + "否则非 MCP 的全局工具会被悄悄收窄掉");
    }

    @Test
    @DisplayName("issue #184 matrix (T,F,*,>0): skillsDisabled + 显式 tool 绑定 → 仅这些 tool + SYSTEM + MCP-rule")
    void skillsDisabledWithToolBindingsScopesToTools() {
        seedBuiltinTool("scoped_tool_probe");
        setSkillsDisabledFlag(true);
        bindingService.setToolBindings(agentId, List.of("scoped_tool_probe"));

        // Auto-clear is opt-in: we want to verify the matrix when both states
        // coexist transiently (i.e. a client wrote tool bindings without
        // touching the flag through the UI). bindSkill/setSkillBindings only
        // clears its own flag; setToolBindings clears tools_disabled, not
        // skills_disabled — so skills_disabled survives here.
        Set<String> effective = bindingService.getEffectiveToolNames(agentId);
        assertNotNull(effective, "存在显式 tool 绑定时不能返回 null");
        assertTrue(effective.contains("scoped_tool_probe"), "用户勾选的工具必须在 allowlist");
        assertTrue(effective.contains("record_lesson"), "system-level 内核工具必须保留");
        assertFalse(effective.isEmpty());
    }

    @Test
    @DisplayName("issue #184 matrix (F,T,0,*): toolsDisabled 无 skill 绑定 → 仅 system-level（不并入 MCP，不继承默认）")
    void toolsDisabledReturnsSystemOnlyAndSkipsMcp() {
        seedMcpServerWithOneTool(8_888_201L, "issue184-mcp", "leaked_probe");
        setToolsDisabledFlag(true);

        Set<String> effective = bindingService.getEffectiveToolNames(agentId);
        assertNotNull(effective, "toolsDisabled=true 时绝不能返回 null（那会让全局默认工具又流回来）");
        assertTrue(effective.contains("record_lesson"), "system-level memory 工具必须保留");
        boolean hasMcp = effective.stream().anyMatch(n -> n != null && n.startsWith("mcp_"));
        assertFalse(hasMcp,
                "toolsDisabled=true 时 enabled MCP 工具绝不能自动并入 —— 否则用户的 '禁用所有工具' 意图被违背。"
                        + "实际 allowlist: " + effective);
    }

    @Test
    @DisplayName("issue #184 matrix (F,T,>0,*): toolsDisabled + skill 绑定 → skill 扩展 + SYSTEM（不并入 MCP）")
    void toolsDisabledKeepsSkillExpansionButSkipsMcp() {
        long skillId = 7_777_801L;
        seedSkill(skillId);
        bindingService.bindSkill(agentId, skillId);
        seedMcpServerWithOneTool(8_888_202L, "issue184-mcp-b", "mcp_should_be_hidden");
        setToolsDisabledFlag(true);

        Set<String> effective = bindingService.getEffectiveToolNames(agentId);
        assertNotNull(effective);
        // Skill expansion is contingent on the resolved manifest declaring
        // allowed_tools, which test fixtures don't seed; the contract we
        // verify here is the MCP suppression + SYSTEM survival. (Skill
        // expansion correctness is exercised in other tests / by the runtime.)
        assertTrue(effective.contains("record_lesson"), "system-level 必须保留");
        boolean hasMcp = effective.stream().anyMatch(n -> n != null && n.startsWith("mcp_"));
        assertFalse(hasMcp, "toolsDisabled=true 即使有 skill 绑定也不能自动并入 MCP");
    }

    @Test
    @DisplayName("issue #184 matrix (T,T,*,*): 两 flag 都开 → 仅 system-level，无 MCP，无默认")
    void bothDisabledReturnsSystemOnly() {
        seedMcpServerWithOneTool(8_888_203L, "issue184-mcp-c", "should_be_hidden");
        setSkillsDisabledFlag(true);
        setToolsDisabledFlag(true);

        Set<String> effective = bindingService.getEffectiveToolNames(agentId);
        assertNotNull(effective);
        assertTrue(effective.contains("record_lesson"), "system-level 必须保留");
        boolean hasMcp = effective.stream().anyMatch(n -> n != null && n.startsWith("mcp_"));
        assertFalse(hasMcp, "两 flag 全开时 MCP 必须完全隐藏");
        // Sanity: the set should be roughly the SYSTEM_LEVEL_TOOLS list —
        // we don't enforce equality (the constant evolves) but it should be
        // substantially smaller than the catalog of every enabled tool.
        assertTrue(effective.size() < 100,
                "两 flag 全开时返回的应该只是 system-level 内核工具，体积明显小于完整默认集。"
                        + "实际大小: " + effective.size());
    }

    @Test
    @DisplayName("issue #184: setSkillBindings 非空保存自动清掉 skills_disabled（数据层不留矛盾态）")
    void setSkillBindingsNonEmptyAutoClearsSkillsDisabledFlag() {
        long skillId = 7_777_802L;
        seedSkill(skillId);
        setSkillsDisabledFlag(true);
        assertTrue(readSkillsDisabledFlag(), "前置：flag 应为 true");

        bindingService.setSkillBindings(agentId, List.of(skillId));

        assertFalse(readSkillsDisabledFlag(),
                "写入非空 skill 绑定应当自动清掉 skills_disabled —— "
                        + "否则 DB 会出现 'disabled=true + 有绑定行' 的矛盾态");
    }

    @Test
    @DisplayName("issue #184: setSkillBindings 空保存不动 flag（toggle 自己拥有该位）")
    void setSkillBindingsEmptySaveDoesNotTouchFlag() {
        // Empty save is ambiguous: it might be "uncheck everything" from the
        // UI that owns skills_disabled separately, or just "no rows". Letting
        // the writer of the flag own it (agent PUT) keeps the toggle the
        // single source of truth.
        setSkillsDisabledFlag(true);
        bindingService.setSkillBindings(agentId, List.of());

        assertTrue(readSkillsDisabledFlag(),
                "空保存不应清掉 flag —— 否则 UI 的'禁用所有技能' toggle 在用户'取消所有勾选'后会被悄悄翻掉");
    }

    @Test
    @DisplayName("issue #184: bindSkill 单次绑定自动清掉 skills_disabled")
    void bindSkillSingleAutoClearsSkillsDisabledFlag() {
        long skillId = 7_777_803L;
        seedSkill(skillId);
        setSkillsDisabledFlag(true);

        bindingService.bindSkill(agentId, skillId);

        assertFalse(readSkillsDisabledFlag(),
                "单条 bindSkill 也算明确的承诺，应当自动清掉 flag");
    }

    @Test
    @DisplayName("issue #184: setToolBindings 非空保存自动清掉 tools_disabled")
    void setToolBindingsNonEmptyAutoClearsToolsDisabledFlag() {
        seedBuiltinTool("autoclear_probe");
        setToolsDisabledFlag(true);
        assertTrue(readToolsDisabledFlag(), "前置：flag 应为 true");

        bindingService.setToolBindings(agentId, List.of("autoclear_probe"));

        assertFalse(readToolsDisabledFlag(),
                "写入非空 tool 绑定应当自动清掉 tools_disabled");
    }

    @Test
    @DisplayName("issue #184: bindTool 单次绑定自动清掉 tools_disabled")
    void bindToolSingleAutoClearsToolsDisabledFlag() {
        setToolsDisabledFlag(true);

        bindingService.bindTool(agentId, "autoclear_single_probe");

        assertFalse(readToolsDisabledFlag(),
                "单条 bindTool 也算明确的承诺，应当自动清掉 flag");
    }

    // ==================== Issue #184 follow-up: skill-discovery deny ====================

    @Test
    @DisplayName("issue #184 follow-up: skills_disabled=true → 屏蔽 listAvailableSkills / load_skill / readSkillFile / runSkillScript / listSkillFiles")
    void skillsDisabledDeniesAllSkillDiscoveryTools() {
        // Verified during smoke test: even with skillsDisabled=true the LLM
        // could call listAvailableSkills and discover the full catalog. The
        // deny layer below subtracts the 5 skill-discovery tools so the opt-out
        // is honored end-to-end, not just in the SKILL.md catalog injection.
        setSkillsDisabledFlag(true);

        Set<String> denied = bindingService.getSkillDiscoveryDeniedTools(agentId);
        assertEquals(5, denied.size(), "应当返回 5 个 skill-discovery 工具名");
        assertTrue(denied.contains("listAvailableSkills"));
        assertTrue(denied.contains("load_skill"));
        assertTrue(denied.contains("readSkillFile"));
        assertTrue(denied.contains("runSkillScript"));
        assertTrue(denied.contains("listSkillFiles"));
    }

    @Test
    @DisplayName("issue #184 follow-up: skills_disabled=false → 不屏蔽任何工具（保留向后兼容）")
    void skillsEnabledReturnsEmptyDenySet() {
        // Default state — no flag, no rows. The deny layer must be a no-op
        // so a legacy agent keeps every skill-discovery tool it had before.
        Set<String> denied = bindingService.getSkillDiscoveryDeniedTools(agentId);
        assertNotNull(denied);
        assertTrue(denied.isEmpty(),
                "skillsDisabled=false 时 deny 集必须为空，否则会误伤未禁用技能的 agent");
    }

    @Test
    @DisplayName("issue #184 follow-up: setSkillBindings 非空时 auto-clear flag → deny 也回到空集")
    void skillDiscoveryDenyTracksAutoClearOfFlag() {
        // Auto-clear contract from the main PR: writing a non-empty binding
        // clears skills_disabled. The deny set must follow — it reads the
        // same flag at call time, so after auto-clear it should be empty.
        long skillId = 7_777_901L;
        seedSkill(skillId);
        setSkillsDisabledFlag(true);
        assertEquals(5, bindingService.getSkillDiscoveryDeniedTools(agentId).size(),
                "前置：flag 开启时 deny 应为 5 个");

        bindingService.setSkillBindings(agentId, List.of(skillId));

        assertTrue(bindingService.getSkillDiscoveryDeniedTools(agentId).isEmpty(),
                "auto-clear 后 deny 必须回到空集，否则用户重新绑定技能后仍然看不到 listAvailableSkills");
    }

    @Test
    @DisplayName("issue #184 follow-up: missing agent → deny 空集（防御性）")
    void skillDiscoveryDenyHandlesMissingAgent() {
        Set<String> denied = bindingService.getSkillDiscoveryDeniedTools(999_999_999L);
        assertNotNull(denied);
        assertTrue(denied.isEmpty(),
                "agent 不存在时 deny 集应为空 —— 严格但不抛错，与 isSkillsDisabled 的契约一致");
    }
}
