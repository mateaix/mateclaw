package vip.mate.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.test.util.ReflectionTestUtils;
import vip.mate.agent.graph.node.*;
import vip.mate.agent.graph.plan.node.PlanSummaryNode;
import vip.mate.agent.graph.guard.TaskFinalizationCoordinator;
import vip.mate.tool.disclosure.ToolDisclosureService;
import java.lang.reflect.Modifier;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentGraphBuilderFinalizationWiringTest {
 @Test void everyProductionGraphBuildWiresPublicationBoundaryByDefault() throws Exception {
  var builder=mock(AgentGraphBuilder.class,CALLS_REAL_METHODS);
  for(var field:AgentGraphBuilder.class.getDeclaredFields()) {
   if(Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive())continue;
   ReflectionTestUtils.setField(builder,field.getName(),mock(field.getType()));
  }
  var window=(vip.mate.agent.context.ConversationWindowManager)ReflectionTestUtils.getField(builder,"conversationWindowManager");
  when(window.getDefaultMaxInputTokens()).thenReturn(16000);
  var disclosure=(ToolDisclosureService)ReflectionTestUtils.getField(builder,"toolDisclosureService");
  when(disclosure.split(any(),anySet(),anySet())).thenReturn(new ToolDisclosureService.ToolDisclosureSplit(List.of(),List.of()));
  var tools=mock(AgentToolSet.class);when(tools.callbacks()).thenReturn(List.of());when(tools.toolBeans()).thenReturn(List.of());
  try(var reasoning=mockConstruction(ReasoningNode.class);
      var terminal=mockConstruction(LimitExceededNode.class);
      var summary=mockConstruction(PlanSummaryNode.class);
      var observations=mockConstruction(vip.mate.agent.graph.observation.ObservationProcessor.class)) {
   assertNotNull(builder.buildReActGraph(tools,mock(ChatModel.class),10,null));
   assertNotNull(builder.buildPlanExecuteGraph(tools,mock(ChatModel.class),10,null));
   assertEquals(1,reasoning.constructed().size());assertEquals(1,terminal.constructed().size());assertEquals(1,summary.constructed().size());
   verify(reasoning.constructed().getFirst()).setFinalizationCoordinator(any(TaskFinalizationCoordinator.class));
   verify(terminal.constructed().getFirst()).setFinalizationCoordinator(any(TaskFinalizationCoordinator.class));
   verify(summary.constructed().getFirst()).setFinalizationCoordinator(any(TaskFinalizationCoordinator.class));
   verify(summary.constructed().getFirst()).setPrefixBudgetPlan(argThat(plan->plan.effectiveMaxTokens()==16000));
  }
 }
 @org.junit.jupiter.params.ParameterizedTest
 @org.junit.jupiter.params.provider.ValueSource(strings={"structured","replay","text"})
 void actualGraphAdapterPublishesOnlyResolvedAcceptedTextMatchingFinalState(String mode) throws Exception {
  var builder=mock(AgentGraphBuilder.class,CALLS_REAL_METHODS);
  for(var field:AgentGraphBuilder.class.getDeclaredFields()) {
   if(Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive())continue;
   ReflectionTestUtils.setField(builder,field.getName(),mock(field.getType()));
  }
  var disclosure=(ToolDisclosureService)ReflectionTestUtils.getField(builder,"toolDisclosureService");
  when(disclosure.split(any(),anySet(),anySet())).thenReturn(new ToolDisclosureService.ToolDisclosureSplit(List.of(),List.of()));
  ReflectionTestUtils.setField(builder,"providerHealthTracker",new vip.mate.llm.failover.ProviderHealthTracker(new vip.mate.llm.failover.ProviderHealthProperties()));
  ReflectionTestUtils.setField(builder,"progressLedgerService",null);
  ReflectionTestUtils.setField(builder,"conversationWindowManager",null);
  ReflectionTestUtils.setField(builder,"markdownNormalizeEnabled",true);
  ReflectionTestUtils.setField(builder,"generatedFileCache",null);
  var tracker=new vip.mate.channel.web.ChatStreamTracker(new com.fasterxml.jackson.databind.ObjectMapper());
  ReflectionTestUtils.setField(builder,"streamTracker",tracker);
  var tools=mock(AgentToolSet.class);when(tools.callbacks()).thenReturn(List.of());
  var model=mock(ChatModel.class);
  String bad="The initial failure was recovered", accepted="##二、美股\n\nExact total: 42. Initial operation failed.";
  String suffix="\",\"taskComplete\":true,\"remainingWork\":false,\"failedCallCount\":1,\"recoveryClaims\":[],\"reason\":\"Remove false recovery\"}";
  java.util.function.Function<String,org.springframework.ai.chat.model.ChatResponse> response=text->new org.springframework.ai.chat.model.ChatResponse(List.of(new org.springframework.ai.chat.model.Generation(new org.springframework.ai.chat.messages.AssistantMessage(text))));
  when(model.stream(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(
    reactor.core.publisher.Flux.just(response.apply(bad)),reactor.core.publisher.Flux.just(response.apply("{\"decision\":\"REVISE"+suffix)),
    reactor.core.publisher.Flux.just(response.apply(accepted)),reactor.core.publisher.Flux.just(response.apply("{\"decision\":\"ACCEPT"+suffix)));
  var graph=builder.buildReActGraph(tools,model,10,null);
  var terminal=new java.util.concurrent.atomic.AtomicReference<vip.mate.agent.runtime.state.RunState>();
  vip.mate.agent.runtime.AgentRuntime seeded=inputs->{
   var values=new HashMap<String,Object>(inputs);
   values.put(vip.mate.agent.graph.state.MateClawStateKeys.ACTION_EXECUTION_LEDGER,
    vip.mate.agent.graph.state.ActionExecutionLedger.fromEvents(List.of(GraphEventPublisher.toolComplete("f","calculate","missing language",false),GraphEventPublisher.toolComplete("s","calculate","42",true))));
   return graph.stream(values).doOnNext(step->terminal.set(step.state()));
  };
  var agent=new vip.mate.agent.graph.StateGraphReActAgent(mock(org.springframework.ai.chat.client.ChatClient.class),
    mock(vip.mate.workspace.conversation.ConversationService.class),seeded,model,null,tools);
  try {
   String published;
   if(mode.equals("text")) {
    var chunks=agent.chatStream("Calculate stages and exact total","normalized-gate").collectList().block(java.time.Duration.ofSeconds(10));
    assertNotNull(chunks);published=String.join("",chunks);
   } else {
    var flux=mode.equals("replay")?agent.chatWithReplayStream("Calculate stages and exact total","normalized-gate","")
        :agent.chatStructuredStream("Calculate stages and exact total","normalized-gate");
    var deltas=flux.collectList().block(java.time.Duration.ofSeconds(10));assertNotNull(deltas);
    published=deltas.stream().map(AgentService.StreamDelta::content).filter(Objects::nonNull).collect(java.util.stream.Collectors.joining());
   }
   String persisted=terminal.get().value(vip.mate.agent.graph.state.MateClawStateKeys.FINAL_ANSWER,"");
   assertEquals(persisted,published);assertFalse(published.contains(bad));assertTrue(published.contains("## 二、美股"));
   assertEquals(4,terminal.get().value(vip.mate.agent.graph.state.MateClawStateKeys.LLM_CALL_COUNT,0));
  } finally {tracker.onShutdown();}
 }
}
