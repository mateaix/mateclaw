package vip.mate.trigger;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import vip.mate.MateClawApplication;
import vip.mate.agent.event.AgentLifecycleEvent;
import vip.mate.trigger.model.TriggerEntity;
import vip.mate.trigger.service.TriggerService;
import vip.mate.workflow.model.WorkflowRunEntity;
import vip.mate.workflow.repository.WorkflowRunMapper;
import vip.mate.workflow.runtime.StubAgentInvoker;
import vip.mate.workflow.runtime.StubAgentInvokerConfig;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Confirms agent_lifecycle is wired as a real event source. The agent
 * module publishes an {@link AgentLifecycleEvent} when an agent is
 * spawned / enabled / disabled / terminated, the trigger bridge maps
 * it into an agent_lifecycle envelope, and a matching trigger fires
 * its target workflow.
 *
 * <p>The test publishes the event directly via the publisher rather
 * than driving full agent-create CRUD — that's the contract the agent
 * module commits to, and skipping the controller keeps the test
 * focused on bridge wiring.
 */
@SpringBootTest(
        classes = MateClawApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:agent_lifecycle_${random.uuid};MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.ai.dashscope.api-key=test-key",
        "spring.main.web-application-type=none",
        "mateclaw.workflow.trigger.async-dispatch=false"
})
@Import({StubAgentInvokerConfig.class, TriggerDispatcherWorkflowTest.StubGraphLoaderConfig.class})
class AgentLifecycleTriggerTest {

    @Autowired private ApplicationEventPublisher publisher;
    @Autowired private TriggerService triggerService;
    @Autowired private WorkflowRunMapper runMapper;
    @Autowired private TriggerDispatcherWorkflowTest.StubGraphLoader stubGraphLoader;
    @Autowired private StubAgentInvoker stubInvoker;

    @Test
    @DisplayName("agent_lifecycle trigger fires when the matching phase + agent is published.")
    void agentLifecycleRoutesToWorkflow() {
        long workspace = 8800L;
        long downstream = 8810L;
        long agentId = 4242L;

        stubInvoker.reset();
        stubInvoker.respond("greeter", "ok");
        stubGraphLoader.reset();
        stubGraphLoader.bind(downstream, 1L,
                "{\"steps\":[{\"name\":\"a\",\"agentName\":\"greeter\","
                        + "\"mode\":{\"type\":\"sequential\"},\"promptTemplate\":\"hi\"}]}");

        TriggerEntity t = new TriggerEntity();
        t.setWorkspaceId(workspace);
        t.setName("on-agent-spawn");
        t.setPatternType("agent_lifecycle");
        t.setPatternJson("{\"agentId\":" + agentId + ",\"phase\":\"spawned\"}");
        t.setTargetType("workflow");
        t.setTargetId(downstream);
        t.setEnabled(true);
        triggerService.create(t);

        publisher.publishEvent(new AgentLifecycleEvent(
                workspace, agentId, "greeter", "spawned", System.currentTimeMillis()));

        List<WorkflowRunEntity> runs = runMapper.selectList(
                new LambdaQueryWrapper<WorkflowRunEntity>().eq(WorkflowRunEntity::getWorkflowId, downstream));
        assertEquals(1, runs.size(),
                "agent_lifecycle event should have triggered exactly one workflow run");
        assertEquals("succeeded", runs.get(0).getState());
    }

    @Test
    @DisplayName("agent_lifecycle trigger keyed on a different phase stays dormant.")
    void wrongPhaseDoesNotMisfire() {
        long workspace = 8900L;
        long downstream = 8910L;
        long agentId = 4243L;

        stubGraphLoader.reset();
        stubGraphLoader.bind(downstream, 1L,
                "{\"steps\":[{\"name\":\"a\",\"agentName\":\"greeter\","
                        + "\"mode\":{\"type\":\"sequential\"},\"promptTemplate\":\"hi\"}]}");

        TriggerEntity t = new TriggerEntity();
        t.setWorkspaceId(workspace);
        t.setName("only-on-terminate");
        t.setPatternType("agent_lifecycle");
        t.setPatternJson("{\"agentId\":" + agentId + ",\"phase\":\"terminated\"}");
        t.setTargetType("workflow");
        t.setTargetId(downstream);
        t.setEnabled(true);
        triggerService.create(t);

        publisher.publishEvent(new AgentLifecycleEvent(
                workspace, agentId, "greeter", "spawned", System.currentTimeMillis()));

        List<WorkflowRunEntity> runs = runMapper.selectList(
                new LambdaQueryWrapper<WorkflowRunEntity>().eq(WorkflowRunEntity::getWorkflowId, downstream));
        assertTrue(runs.isEmpty(),
                "phase mismatch should leave the trigger dormant");
    }
}
