package vip.mate.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import vip.mate.MateClawApplication;
import vip.mate.agent.model.AgentEntity;
import vip.mate.exception.MateClawException;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pre-flight {@code (workspace_id, name)} uniqueness check that
 * accompanies the V102 unique index.
 *
 * <p>The DB index alone would surface as {@code DataIntegrityViolation}
 * with a vendor-specific message; the service-layer pre-check converts
 * that to a stable {@code err.agent.duplicate_name} business code so the
 * UI can show a localized message and clients can branch deterministically.
 * These tests pin both the rejection paths and the false-positive guard
 * (a same-name UPDATE on the row itself must not block).
 */
@SpringBootTest(
        classes = MateClawApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:agent_unique_test_${random.uuid};MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.ai.dashscope.api-key=test-key",
        "spring.main.web-application-type=none"
})
class AgentServiceUniquenessTest {

    /**
     * Tests share one in-memory DB instance (a single
     * {@code @SpringBootTest} class shares its application context across
     * methods). Workspace ids are derived from this counter so concurrent
     * methods can't trample one another's rows.
     */
    private static final AtomicLong WS_SEQ = new AtomicLong(50_000L);

    @Autowired
    private AgentService agentService;

    private long workspaceA;
    private long workspaceB;

    @BeforeEach
    void setUp() {
        workspaceA = WS_SEQ.getAndIncrement();
        workspaceB = WS_SEQ.getAndIncrement();
    }

    private AgentEntity newAgent(String name, long workspaceId) {
        AgentEntity a = new AgentEntity();
        a.setName(name);
        a.setDescription("uniqueness test agent");
        a.setAgentType("react");
        a.setSystemPrompt("");
        a.setMaxIterations(10);
        a.setWorkspaceId(workspaceId);
        return a;
    }

    @Test
    @DisplayName("createAgent 拒绝同 workspace 同名（body code = 409 / msgKey = err.agent.duplicate_name）")
    void createRejectsDuplicateNameInSameWorkspace() {
        agentService.createAgent(newAgent("Alpha", workspaceA));

        MateClawException ex = assertThrows(MateClawException.class,
                () -> agentService.createAgent(newAgent("Alpha", workspaceA)));
        assertEquals(409, ex.getCode(), "应返回 409 业务码");
        assertEquals("err.agent.duplicate_name", ex.getMsgKey());
    }

    @Test
    @DisplayName("createAgent 允许不同 workspace 同名（隔离边界生效）")
    void createAllowsSameNameInDifferentWorkspace() {
        AgentEntity a = agentService.createAgent(newAgent("Bravo", workspaceA));
        AgentEntity b = agentService.createAgent(newAgent("Bravo", workspaceB));

        assertNotNull(a.getId());
        assertNotNull(b.getId());
        assertNotEquals(a.getId(), b.getId());
    }

    @Test
    @DisplayName("createAgent 拒绝空名（fail-fast 在 unique 检查之前）")
    void createRejectsBlankName() {
        AgentEntity blank = newAgent(null, workspaceA);
        MateClawException ex = assertThrows(MateClawException.class,
                () -> agentService.createAgent(blank));
        assertEquals(400, ex.getCode());
        assertEquals("err.agent.name_required", ex.getMsgKey());
    }

    @Test
    @DisplayName("updateAgent 拒绝把名字改成 workspace 内已有的别人")
    void updateRejectsRenamingToExistingName() {
        AgentEntity first = agentService.createAgent(newAgent("Charlie", workspaceA));
        AgentEntity second = agentService.createAgent(newAgent("Delta", workspaceA));

        // Try renaming "Delta" → "Charlie" inside the same workspace.
        second.setName("Charlie");
        MateClawException ex = assertThrows(MateClawException.class,
                () -> agentService.updateAgent(second));
        assertEquals(409, ex.getCode());
        assertEquals("err.agent.duplicate_name", ex.getMsgKey());

        // The other row must not have been touched.
        assertEquals("Charlie", agentService.getAgent(first.getId()).getName());
    }

    @Test
    @DisplayName("updateAgent 元数据修改不触发误报（excludeId 跳过自己）")
    void updateAllowsMetadataEditWithoutFalsePositive() {
        AgentEntity created = agentService.createAgent(newAgent("Echo", workspaceA));

        // Edit description only, keep the same name. The unique check
        // should detect "no name change" and skip the SELECT entirely;
        // even if it didn't, the excludeId branch would filter self out.
        created.setDescription("edited");
        assertDoesNotThrow(() -> agentService.updateAgent(created));
        assertEquals("edited", agentService.getAgent(created.getId()).getDescription());
    }

    @Test
    @DisplayName("updateAgent 改名为新值（不冲突）允许")
    void updateAllowsRenameToUnusedName() {
        AgentEntity created = agentService.createAgent(newAgent("Foxtrot", workspaceA));
        created.setName("Foxtrot-renamed");
        assertDoesNotThrow(() -> agentService.updateAgent(created));
        assertEquals("Foxtrot-renamed",
                agentService.getAgent(created.getId()).getName());
    }
}
