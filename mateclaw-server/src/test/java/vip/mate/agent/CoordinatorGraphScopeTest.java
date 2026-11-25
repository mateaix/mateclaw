package vip.mate.agent;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.binding.service.AgentBindingService;
import vip.mate.team.service.TeamCoordinatorPolicy;
import vip.mate.skill.runtime.SkillCatalogRenderer;
import java.lang.reflect.Modifier;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CoordinatorGraphScopeTest {
    private <T> T realWithMocks(Class<T> type) {
        T instance = mock(type, CALLS_REAL_METHODS);
        for (var field : type.getDeclaredFields())
            if (!Modifier.isStatic(field.getModifiers()) && !field.getType().isPrimitive())
                ReflectionTestUtils.setField(instance, field.getName(), mock(field.getType()));
        return instance;
    }
    @Test void restrictionPrecedesLegacyNullBindingsAndSystemToolUnion() {
        var bindings = realWithMocks(AgentBindingService.class);
        var policy = mock(TeamCoordinatorPolicy.class);
        ReflectionTestUtils.setField(bindings,"coordinatorPolicy",policy);
        when(policy.isCoordinator(1L)).thenReturn(true);
        assertEquals(TeamCoordinatorPolicy.TOOLS,bindings.getEffectiveToolNames(1L));
        verifyNoInteractions(ReflectionTestUtils.getField(bindings,"agentMapper"));
        when(policy.isCoordinator(1L)).thenReturn(false);
        // Ordinary null binding behavior remains available after disabling mode.
        assertNull(bindings.getEffectiveToolNames(1L));
    }
    @Test void coordinatorPromptKeepsIdentityButSuppressesExecutionWikiAndSkills() {
        var graph = realWithMocks(AgentGraphBuilder.class);
        var policy = mock(TeamCoordinatorPolicy.class);
        ReflectionTestUtils.setField(graph,"coordinatorPolicy",policy);
        when(policy.isCoordinator(1L)).thenReturn(true);
        var agent = new AgentEntity(); agent.setId(1L); agent.setSystemPrompt("Team identity");
        String prompt = ReflectionTestUtils.invokeMethod(graph,"buildEnhancedPrompt",agent,true,1000,TeamCoordinatorPolicy.TOOLS);
        assertTrue(prompt.contains("Team identity")); assertTrue(prompt.contains("team_tasks"));
        assertFalse(prompt.contains("execute commands")); assertFalse(prompt.contains("Provider-native Search"));
        verifyNoInteractions(ReflectionTestUtils.getField(graph,"wikiContextService"));
        SkillCatalogRenderer renderer = ReflectionTestUtils.invokeMethod(graph,"buildSkillCatalogRenderer",agent,TeamCoordinatorPolicy.TOOLS,1000);
        assertEquals("",renderer.render(Set.of("some-loaded-skill")));
        verifyNoInteractions(ReflectionTestUtils.getField(graph,"skillRuntimeService"));
    }
    @Test void updatingCoordinatorToDshRejectedBeforeMutation() {
        var service = realWithMocks(AgentService.class);
        var policy = mock(TeamCoordinatorPolicy.class);
        ReflectionTestUtils.setField(service,"coordinatorPolicy",policy);
        var agent = new AgentEntity(); agent.setId(1L); agent.setRuntimeType("dsh");
        doThrow(new IllegalArgumentException("native runtime required")).when(policy).requireSupportedRuntime(agent);
        assertThrows(IllegalArgumentException.class, () -> service.updateAgent(agent));
        verifyNoInteractions(ReflectionTestUtils.getField(service,"agentMapper"));
    }

    @Test void primaryAndFallbackClientSeamAlwaysPassesCoordinatorScope() {
        var graph = realWithMocks(AgentGraphBuilder.class);
        var policy = mock(TeamCoordinatorPolicy.class);
        ReflectionTestUtils.setField(graph,"coordinatorPolicy",policy);
        when(policy.isCoordinator(1L)).thenReturn(true);
        var factory = (vip.mate.llm.chatmodel.ProviderChatModelFactory) ReflectionTestUtils.getField(graph,"chatModelFactory");
        var primary = new vip.mate.llm.model.ModelConfigEntity(); primary.setProvider("primary");
        var fallback = new vip.mate.llm.model.ModelConfigEntity(); fallback.setProvider("fallback");
        var retry = org.springframework.retry.support.RetryTemplate.builder().maxAttempts(1).build();
        ReflectionTestUtils.invokeMethod(graph,"buildAgentChatModel",primary,retry,1L);
        ReflectionTestUtils.invokeMethod(graph,"buildAgentChatModel",fallback,retry,1L);
        verify(factory).buildFor(primary,retry,true); verify(factory).buildFor(fallback,retry,true);
        when(policy.isCoordinator(1L)).thenReturn(false);
        ReflectionTestUtils.invokeMethod(graph,"buildAgentChatModel",primary,retry,1L);
        verify(factory).buildFor(primary,retry);
    }

    @Test void teamSettingsEventEvictsLeadCacheForNextTurn() {
        var service = realWithMocks(AgentService.class);
        var cache = new java.util.concurrent.ConcurrentHashMap<Long,java.util.Map<String,BaseAgent>>();
        cache.put(1L,new java.util.HashMap<>()); cache.put(2L,new java.util.HashMap<>());
        ReflectionTestUtils.setField(service,"agentInstances",cache);
        service.onTeamChanged(new vip.mate.team.event.TeamChangedEvent(java.util.List.of(1L)));
        assertFalse(cache.containsKey(1L)); assertTrue(cache.containsKey(2L));
    }

    @Test void coordinatorPlanGraphCannotResumeGeneralAssignedStepDelegation() throws Exception {
        var graph = realWithMocks(AgentGraphBuilder.class);
        var policy = mock(TeamCoordinatorPolicy.class);
        ReflectionTestUtils.setField(graph,"coordinatorPolicy",policy);
        when(policy.isCoordinator(1L)).thenReturn(true);
        var disclosure = (vip.mate.tool.disclosure.ToolDisclosureService)ReflectionTestUtils.getField(graph,"toolDisclosureService");
        when(disclosure.split(any(),anySet(),anySet())).thenReturn(new vip.mate.tool.disclosure.ToolDisclosureService.ToolDisclosureSplit(java.util.List.of(),java.util.List.of()));
        var tools = AgentToolSet.fromCallbacks(java.util.List.of(),java.util.List.of());
        var delegateCaptor = org.mockito.ArgumentCaptor.forClass(vip.mate.tool.builtin.DelegateAgentTool.class);
        try (var steps = mockConstruction(vip.mate.agent.graph.plan.node.StepExecutionNode.class);
             var planners = mockConstruction(vip.mate.agent.graph.plan.node.PlanGenerationNode.class, (node,ctx) -> {
                 assertNull(ctx.arguments().get(5)); // no automatic goal creation service
             })) {
            graph.buildPlanExecuteGraph(tools,mock(org.springframework.ai.chat.model.ChatModel.class),10,null,null,1L);
            var step = steps.constructed().getFirst();
            verify(step).setDelegateAgentTool(delegateCaptor.capture());
            assertNull(delegateCaptor.getValue());
            verify(step).setGoalDecisionAdapter(isNull());
            verify(planners.constructed().getFirst()).setTeamPlanBridge(same((vip.mate.team.service.TeamPlanBridge)ReflectionTestUtils.getField(graph,"teamPlanBridge")));
        }
        var planning = mock(vip.mate.planning.service.PlanningService.class);
        when(planning.getStepAssignedAgent(77L,0)).thenReturn(2L); // persisted before mode was enabled
        var helper = mock(vip.mate.agent.graph.NodeStreamingChatHelper.class);
        when(helper.streamCall(any(),any(),anyString(),anyString())).thenReturn(
                new vip.mate.agent.graph.NodeStreamingChatHelper.StreamResult("review complete","",
                        new org.springframework.ai.chat.messages.AssistantMessage("review complete"),java.util.List.of(),false,1,1));
        var resumed = new vip.mate.agent.graph.plan.node.StepExecutionNode(
                mock(org.springframework.ai.chat.model.ChatModel.class),tools,
                mock(vip.mate.agent.graph.executor.ToolExecutionExecutor.class),planning,null,null,helper,null, loaded -> "");
        resumed.setDelegateAgentTool(delegateCaptor.getValue());
        resumed.apply(new vip.mate.agent.runtime.state.RunState(java.util.Map.of(
                vip.mate.agent.graph.plan.state.PlanStateKeys.PLAN_ID,77L,
                vip.mate.agent.graph.plan.state.PlanStateKeys.PLAN_STEPS,java.util.List.of("review existing results"),
                vip.mate.agent.graph.state.MateClawStateKeys.CONVERSATION_ID,"persisted-plan",
                vip.mate.agent.graph.state.MateClawStateKeys.AGENT_ID,"1")));
        verify(planning).getStepAssignedAgent(77L,0);
        verify(helper).streamCall(any(),any(),anyString(),anyString());
        verifyNoInteractions(ReflectionTestUtils.getField(graph,"delegateAgentTool"));
    }
}
