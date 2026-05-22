package vip.mate.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import vip.mate.MateClawApplication;
import vip.mate.agent.binding.service.AgentBindingService;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.agent.model.AgentEntity;

import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the agent authoring tool against a real Spring context so the
 * create-then-bind sequence runs through {@link AgentService} and
 * {@link AgentBindingService} exactly as it would at chat time. Builtin
 * skills are seeded on startup, so skill-name resolution hits real rows.
 */
@SpringBootTest(
        classes = MateClawApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:agent_authoring_test_${random.uuid};MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.ai.dashscope.api-key=test-key",
        "spring.main.web-application-type=none"
})
class AgentAuthoringToolTest {

    private static final AtomicLong WS_SEQ = new AtomicLong(70_000L);

    @Autowired
    private AgentAuthoringTool tool;
    @Autowired
    private AgentService agentService;
    @Autowired
    private AgentBindingService bindingService;

    private final ObjectMapper mapper = new ObjectMapper();

    private long workspaceId;

    @BeforeEach
    void setUp() {
        workspaceId = WS_SEQ.getAndIncrement();
    }

    private ToolContext ctxFor(long ws) {
        return ChatOrigin.web("conv-" + ws, "123", ws, null).toToolContext();
    }

    @Test
    @DisplayName("create_employee 在 ChatOrigin 的 workspace 内创建 Agent，无能力名时继承全局默认")
    void createsGeneralistInOriginWorkspace() throws Exception {
        String json = tool.create_employee(
                "generalist-helper", "general assistant", "You help with anything.",
                null, null, null, null, ctxFor(workspaceId));

        JsonNode node = mapper.readTree(json);
        long agentId = Long.parseLong(node.get("agentId").asText());

        AgentEntity created = agentService.getAgent(agentId);
        assertEquals("generalist-helper", created.getName());
        assertEquals(workspaceId, created.getWorkspaceId());
        assertEquals("react", created.getAgentType());
        // Creator attribution parsed from the numeric requesterId.
        assertEquals(123L, created.getCreatorUserId());
        // No bindings declared → inherits global defaults (null sentinel).
        assertNull(bindingService.getBoundSkillIds(agentId));
        assertNull(bindingService.getBoundToolNames(agentId));
    }

    @Test
    @DisplayName("create_employee 绑定指定的内置技能（按名解析为 id）")
    void bindsRequestedBuiltinSkill() throws Exception {
        String json = tool.create_employee(
                "planner-employee", "planning specialist", "You break goals into plans.",
                "react", null, "[\"make_plan\"]", null, ctxFor(workspaceId));

        JsonNode node = mapper.readTree(json);
        long agentId = Long.parseLong(node.get("agentId").asText());

        Set<Long> boundSkills = bindingService.getBoundSkillIds(agentId);
        assertNotNull(boundSkills, "declaring a skill must create a binding set");
        assertEquals(1, boundSkills.size());
        // The summary echoes the bound skill name.
        assertTrue(node.get("skillsBound").toString().contains("make_plan"));
    }

    @Test
    @DisplayName("create_employee 缺少 workspace 上下文时拒绝执行")
    void rejectsWithoutWorkspace() {
        String result = tool.create_employee(
                "no-ws", "x", "y", null, null, null, null, ChatOrigin.EMPTY.toToolContext());
        assertTrue(result.startsWith("[error]"));
    }

    @Test
    @DisplayName("create_employee 重名返回友好错误而非抛出")
    void duplicateNameReturnsFriendlyError() {
        ToolContext ctx = ctxFor(workspaceId);
        tool.create_employee("dup-employee", "first", "p", null, null, null, null, ctx);
        String second = tool.create_employee("dup-employee", "second", "p", null, null, null, null, ctx);
        assertTrue(second.startsWith("[error]"), "duplicate name should surface as a friendly error");
    }

    @Test
    @DisplayName("list_capability_catalog 返回技能与工具清单")
    void catalogReturnsSkillsAndTools() throws Exception {
        String json = tool.list_capability_catalog(ctxFor(workspaceId));
        JsonNode node = mapper.readTree(json);
        assertTrue(node.has("skills"));
        assertTrue(node.has("tools"));
        assertTrue(node.get("skills").isArray());
        // make_plan is a seeded builtin skill, so the catalog must surface it.
        boolean hasMakePlan = false;
        for (JsonNode s : node.get("skills")) {
            if ("make_plan".equals(s.path("name").asText())) { hasMakePlan = true; break; }
        }
        assertTrue(hasMakePlan, "seeded builtin skill make_plan should appear in the catalog");
    }
}
