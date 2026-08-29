package vip.mate.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import vip.mate.agent.AgentService;
import vip.mate.agent.delegation.SubagentRegistry;
import vip.mate.agent.model.AgentEntity;
import vip.mate.channel.web.ChatStreamTracker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentRuntimeAggregatorTest {

    private static final long WORKSPACE_A = 10L;
    private static final long WORKSPACE_B = 20L;

    @Test
    void workspaceSnapshotExcludesRunsAndSubagentsFromOtherWorkspaces() {
        ChatStreamTracker tracker = new ChatStreamTracker(new ObjectMapper());
        tracker.register("conv-a");
        tracker.bindRunMeta("conv-a", 100L, "alice");
        tracker.register("conv-b");
        tracker.bindRunMeta("conv-b", 200L, "bob");
        tracker.register("conv-unknown");

        SubagentRegistry subagents = new SubagentRegistry();
        subagents.register("conv-a", "child-a", 100L, "task a", null);
        subagents.register("conv-b", "child-b", 200L, "task b", null);

        AgentService agents = mock(AgentService.class);
        when(agents.getAgent(100L)).thenReturn(agent(100L, WORKSPACE_A, "A"));
        when(agents.getAgent(200L)).thenReturn(agent(200L, WORKSPACE_B, "B"));

        AgentRuntimeAggregator aggregator = new AgentRuntimeAggregator(tracker, subagents, agents);

        AgentRuntimeAggregator.RuntimeSnapshot snapshot = aggregator.snapshot(WORKSPACE_A);

        assertThat(snapshot.runs())
                .extracting(AgentRuntimeAggregator.RunCard::conversationId)
                .containsExactly("conv-a");
        assertThat(snapshot.subagents())
                .extracting(AgentRuntimeAggregator.SubagentCard::childConversationId)
                .containsExactly("child-a");
        assertThat(snapshot.summary().running()).isEqualTo(1);
        assertThat(snapshot.summary().subagentsActive()).isEqualTo(1);
    }

    @Test
    void runBelongsToWorkspaceOnlyWhenAgentMetadataMatches() {
        ChatStreamTracker tracker = new ChatStreamTracker(new ObjectMapper());
        tracker.register("conv-a");
        tracker.bindRunMeta("conv-a", 100L, "alice");
        tracker.register("conv-b");
        tracker.bindRunMeta("conv-b", 200L, "bob");

        AgentService agents = mock(AgentService.class);
        when(agents.getAgent(100L)).thenReturn(agent(100L, WORKSPACE_A, "A"));
        when(agents.getAgent(200L)).thenReturn(agent(200L, WORKSPACE_B, "B"));

        AgentRuntimeAggregator aggregator = new AgentRuntimeAggregator(
                tracker, new SubagentRegistry(), agents);

        assertThat(aggregator.runBelongsToWorkspace("conv-a", WORKSPACE_A)).isTrue();
        assertThat(aggregator.runBelongsToWorkspace("conv-b", WORKSPACE_A)).isFalse();
        assertThat(aggregator.runBelongsToWorkspace("missing", WORKSPACE_A)).isFalse();
    }

    private static AgentEntity agent(Long id, Long workspaceId, String name) {
        AgentEntity agent = new AgentEntity();
        agent.setId(id);
        agent.setWorkspaceId(workspaceId);
        agent.setName(name);
        return agent;
    }
}
