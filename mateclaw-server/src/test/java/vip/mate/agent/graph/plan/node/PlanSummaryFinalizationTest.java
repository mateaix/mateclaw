package vip.mate.agent.graph.plan.node;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import vip.mate.llm.chatmodel.ThinkingLevelHolder;
import vip.mate.agent.GraphEventPublisher;
import vip.mate.agent.graph.NodeStreamingChatHelper;
import vip.mate.agent.graph.guard.TaskFinalizationCoordinator;
import vip.mate.agent.graph.plan.state.PlanStateKeys;
import vip.mate.agent.graph.state.MateClawStateKeys;
import vip.mate.agent.runtime.state.RunState;
import vip.mate.planning.service.PlanningService;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
class PlanSummaryFinalizationTest {
 @Test void oversizedPlanOutputFailsIncompleteWithoutCheckingOrRestarting() throws Exception {
  var helper=mock(NodeStreamingChatHelper.class);var service=mock(PlanningService.class);
  var node=new PlanSummaryNode(mock(ChatModel.class),service,helper);node.setFinalizationCoordinator(new TaskFinalizationCoordinator(helper,null));
  String full="x".repeat(120000)+" actual-total";
  var state=new RunState(Map.of(PlanStateKeys.PLAN_ID,42L,PlanStateKeys.GOAL,"aggregate",
    PlanStateKeys.COMPLETED_RESULTS,List.of("result retained elsewhere"),MateClawStateKeys.PENDING_EVENTS,List.of(GraphEventPublisher.toolComplete("aggregate","calc",full,true))));
  when(helper.streamCallBufferedContent(any(),any(),any(),anyString())).thenReturn(new NodeStreamingChatHelper.StreamResult("table","",null,List.of(),false,10,5));
  var out=node.apply(state);assertEquals("incomplete",out.get(MateClawStateKeys.FINISH_REASON));
  assertFalse(String.valueOf(out.get(PlanStateKeys.FINAL_SUMMARY)).contains("table"));
  verify(helper,never()).streamCallSilent(any(),any(),any(),anyString());verify(service,never()).completePlan(anyLong(),anyString());
  assertEquals(List.of("result retained elsewhere"),state.value(PlanStateKeys.COMPLETED_RESULTS,List.of()));
 }
 @Test void planReviewerGetsCompleteCanonicalNumericResultBeyondPreview() throws Exception {
  var helper=mock(NodeStreamingChatHelper.class);var service=mock(PlanningService.class);
  var node=new PlanSummaryNode(mock(ChatModel.class),service,helper);node.setFinalizationCoordinator(new TaskFinalizationCoordinator(helper,null));
  String full="x".repeat(600)+" totalSquareSum=2094116873200";
  var state=new RunState(Map.of(PlanStateKeys.PLAN_ID,42L,PlanStateKeys.GOAL,"aggregate",
    PlanStateKeys.COMPLETED_RESULTS,List.of(full),MateClawStateKeys.PENDING_EVENTS,List.of(GraphEventPublisher.toolComplete("aggregate","calc",full,true))));
  when(helper.streamCallBufferedContent(any(),any(),any(),anyString())).thenReturn(new NodeStreamingChatHelper.StreamResult("table","",null,List.of(),false,10,5));
  when(helper.streamCallSilent(any(),any(),any(),anyString())).thenAnswer(call->{
    assertTrue(((org.springframework.ai.chat.prompt.Prompt)call.getArgument(1)).getContents().contains("totalSquareSum=2094116873200"));
    return new NodeStreamingChatHelper.StreamResult("{\"decision\":\"ACCEPT\",\"taskComplete\":true,\"remainingWork\":false,\"failedCallCount\":0,\"recoveryClaims\":[],\"reason\":\"checked\"}","",null,List.of(),false,7,3);
  });
  var out=node.apply(state);assertEquals("table",out.get(PlanStateKeys.FINAL_SUMMARY));verify(service).completePlan(42L,"table");
 }
 @Test void malformedSummaryCheckCannotCompletePlanOrPublishDraft() throws Exception {
  var helper=mock(NodeStreamingChatHelper.class);var service=mock(PlanningService.class);
  var node=new PlanSummaryNode(mock(ChatModel.class),service,helper);
  try {node.getClass().getMethod("setFinalizationCoordinator",TaskFinalizationCoordinator.class)
    .invoke(node,new TaskFinalizationCoordinator(helper,null));}
  catch(NoSuchMethodException missing){throw new AssertionError("Plan summaries require publication boundary",missing);}
  var candidate=new NodeStreamingChatHelper.StreamResult("Failed operation recovered","",new AssistantMessage("Failed operation recovered"),List.of(),false,10,5);
  when(helper.streamCallBufferedContent(any(),any(),any(),anyString())).thenReturn(candidate);
  when(helper.streamCallSilent(any(),any(),any(),anyString())).thenReturn(new NodeStreamingChatHelper.StreamResult("bad json","",null,List.of(),false,7,3));
  var out=node.apply(new RunState(Map.of(PlanStateKeys.PLAN_ID,42L,PlanStateKeys.GOAL,"all stages",
    PlanStateKeys.COMPLETED_RESULTS,List.of("42"),MateClawStateKeys.PENDING_EVENTS,List.of(GraphEventPublisher.toolComplete("f","calc","failed",false)))));
  assertEquals("incomplete",out.get(MateClawStateKeys.FINISH_REASON));assertEquals(false,out.get(MateClawStateKeys.CONTENT_STREAMED));
  assertFalse(String.valueOf(out.get(PlanStateKeys.FINAL_SUMMARY)).contains("Failed operation recovered"));
  assertEquals(2,out.get(MateClawStateKeys.LLM_CALL_COUNT)); assertEquals(17,out.get(MateClawStateKeys.PROMPT_TOKENS));
  verify(service,never()).completePlan(anyLong(),anyString());
 }
 @Test void summaryRevisionIsBoundedAndNeverRestartsCompletedPlan() throws Exception {
  var helper=mock(NodeStreamingChatHelper.class);var service=mock(PlanningService.class);
  var node=new PlanSummaryNode(mock(ChatModel.class),service,helper);
  node.setFinalizationCoordinator(new TaskFinalizationCoordinator(helper,null));
  var candidate=new NodeStreamingChatHelper.StreamResult("bad recovery","",new AssistantMessage("bad recovery"),List.of(),false,10,5);
  var corrected=new NodeStreamingChatHelper.StreamResult("Initial operation failed; unrelated result 42","",new AssistantMessage("Initial operation failed; unrelated result 42"),List.of(),false,10,5);
  when(helper.streamCallBufferedContent(any(),any(),any(),anyString())).thenReturn(candidate,corrected);
  String prefix="{\"decision\":\"",suffix="\",\"taskComplete\":true,\"remainingWork\":false,\"failedCallCount\":1,\"recoveryClaims\":[],\"reason\":\"Remove unsupported recovery\"}";
  when(helper.streamCallSilent(any(),any(),any(),anyString())).thenReturn(
    new NodeStreamingChatHelper.StreamResult(prefix+"REVISE"+suffix,"",null,List.of(),false,7,3),
    new NodeStreamingChatHelper.StreamResult(prefix+"ACCEPT"+suffix,"",null,List.of(),false,7,3));
  var out=node.apply(new RunState(Map.of(PlanStateKeys.PLAN_ID,42L,PlanStateKeys.GOAL,"all stages",
    PlanStateKeys.COMPLETED_RESULTS,List.of("42"),MateClawStateKeys.PENDING_EVENTS,List.of(GraphEventPublisher.toolComplete("f","calc","failed",false)))));
  assertEquals(corrected.text(),out.get(PlanStateKeys.FINAL_SUMMARY));assertEquals(4,out.get(MateClawStateKeys.LLM_CALL_COUNT));
  assertEquals(34,out.get(MateClawStateKeys.PROMPT_TOKENS));assertEquals(1,out.get(MateClawStateKeys.FINALIZATION_ATTEMPTS));
  verify(service).completePlan(42L,corrected.text());verify(service,never()).markPlanFailed(anyLong(),anyString());
 }
 @Test void emptySummaryRetainsCompletedResultsFallbackAfterAcceptedCheck() throws Exception {
  var helper=mock(NodeStreamingChatHelper.class);var service=mock(PlanningService.class);
  var node=new PlanSummaryNode(mock(ChatModel.class),service,helper);node.setFinalizationCoordinator(new TaskFinalizationCoordinator(helper,null));
  when(helper.streamCallBufferedContent(any(),any(),any(),anyString())).thenReturn(new NodeStreamingChatHelper.StreamResult("","",null,List.of(),false,10,5));
  when(helper.streamCallSilent(any(),any(),any(),anyString())).thenReturn(new NodeStreamingChatHelper.StreamResult(
    "{\"decision\":\"ACCEPT\",\"taskComplete\":true,\"remainingWork\":false,\"failedCallCount\":0,\"recoveryClaims\":[],\"reason\":\"Step result supports fallback\"}","",null,List.of(),false,7,3));
  var out=node.apply(new RunState(Map.of(PlanStateKeys.PLAN_ID,42L,PlanStateKeys.GOAL,"all stages",PlanStateKeys.COMPLETED_RESULTS,List.of("Exact step result 42"))));
  assertTrue(String.valueOf(out.get(PlanStateKeys.FINAL_SUMMARY)).contains("Exact step result 42"));
  assertEquals(false,out.get(MateClawStateKeys.CONTENT_STREAMED));verify(helper).streamCallSilent(any(),any(),any(),anyString());
  verify(service).completePlan(eq(42L),anyString());
 }
 @Test void guardedSummaryPreservesWorkingContextAnd413DiagnosisWithOriginalRecordsRetained() throws Exception {
  var helper=mock(NodeStreamingChatHelper.class);var service=mock(PlanningService.class);
  var node=new PlanSummaryNode(mock(ChatModel.class),service,helper);node.setFinalizationCoordinator(new TaskFinalizationCoordinator(helper,null));
  when(helper.streamCallBufferedContent(any(),any(),any(),anyString())).thenAnswer(call->{
    String prompt=((org.springframework.ai.chat.prompt.Prompt)call.getArgument(1)).getContents();
    assertTrue(prompt.contains("Respond in French and retain exact table format"));
    assertTrue(prompt.contains("语言一致性"));
    assertTrue(prompt.contains("明确指定"));
    return new NodeStreamingChatHelper.StreamResult("","",null,List.of(),false,10,5,false,"413",NodeStreamingChatHelper.ErrorType.PAYLOAD_TOO_LARGE);
  });
  var state=new RunState(Map.of(PlanStateKeys.PLAN_ID,42L,PlanStateKeys.GOAL,"all stages",
    PlanStateKeys.WORKING_CONTEXT,"Respond in French and retain exact table format",PlanStateKeys.COMPLETED_RESULTS,List.of("Exact step result 42")));
  var out=node.apply(state);
  assertEquals("incomplete",out.get(MateClawStateKeys.FINISH_REASON));
  assertFalse(String.valueOf(out.get(PlanStateKeys.FINAL_SUMMARY)).contains("Exact step result 42"));
  assertEquals(List.of("Exact step result 42"),state.value(PlanStateKeys.COMPLETED_RESULTS,List.of()));
  assertTrue(String.valueOf(out.get(MateClawStateKeys.PENDING_EVENTS)).contains("incomplete"));
 }
 @Test void cancelledSummaryCannotPublishFallbackOrRestartPlan() throws Exception {
  var helper=mock(NodeStreamingChatHelper.class);var service=mock(PlanningService.class);
  var node=new PlanSummaryNode(mock(ChatModel.class),service,helper);node.setFinalizationCoordinator(new TaskFinalizationCoordinator(helper,null));
  when(helper.streamCallBufferedContent(any(),any(),any(),anyString())).thenThrow(new java.util.concurrent.CancellationException("stop"));
  var out=node.apply(new RunState(Map.of(PlanStateKeys.PLAN_ID,42L,PlanStateKeys.GOAL,"all stages",PlanStateKeys.COMPLETED_RESULTS,List.of("42"))));
  assertEquals("stopped",out.get(MateClawStateKeys.FINISH_REASON));assertEquals("",out.get(PlanStateKeys.FINAL_SUMMARY));
  assertEquals(1,out.get(MateClawStateKeys.LLM_CALL_COUNT));verify(service,never()).completePlan(anyLong(),anyString());
 }
 @Test void summaryCandidatePreservesConfiguredOutputWhileCheckerIsSeparatelyBounded() throws Exception {
  var helper=mock(NodeStreamingChatHelper.class);var service=mock(PlanningService.class);var model=mock(ChatModel.class);
  when(model.getDefaultOptions()).thenReturn(org.springframework.ai.openai.OpenAiChatOptions.builder().model("test").maxTokens(5000).build());
  var node=new PlanSummaryNode(model,service,helper);node.setFinalizationCoordinator(new TaskFinalizationCoordinator(helper,null));
  when(helper.streamCallBufferedContent(any(),any(),any(),anyString())).thenAnswer(call->{
    assertEquals(5000,((org.springframework.ai.chat.prompt.Prompt)call.getArgument(1)).getOptions().getMaxTokens());
    return new NodeStreamingChatHelper.StreamResult("result 42","",new AssistantMessage("result 42"),List.of(),false,10,5);
  });
  when(helper.streamCallSilent(any(),any(),any(),anyString())).thenAnswer(call->{
    assertEquals(1024,((org.springframework.ai.chat.prompt.Prompt)call.getArgument(1)).getOptions().getMaxTokens());
    return new NodeStreamingChatHelper.StreamResult("{\"decision\":\"ACCEPT\",\"taskComplete\":true,\"remainingWork\":false,\"failedCallCount\":0,\"recoveryClaims\":[],\"reason\":\"checked\"}","",null,List.of(),false,7,3);
  });
  var out=node.apply(new RunState(Map.of(PlanStateKeys.PLAN_ID,42L,PlanStateKeys.GOAL,"all stages",PlanStateKeys.COMPLETED_RESULTS,List.of("42"))));
  assertEquals("result 42",out.get(PlanStateKeys.FINAL_SUMMARY));
 }
 @Test void blankSummaryCannotPublishUnverifiedRecoveryFromStepResultFallback() throws Exception {
  var helper=mock(NodeStreamingChatHelper.class);var service=mock(PlanningService.class);
  var node=new PlanSummaryNode(mock(ChatModel.class),service,helper);node.setFinalizationCoordinator(new TaskFinalizationCoordinator(helper,null));
  String dishonest="The failed print(1) was recovered by unrelated later calculations";
  when(helper.streamCallBufferedContent(any(),any(),any(),anyString())).thenReturn(new NodeStreamingChatHelper.StreamResult("","",null,List.of(),false,10,5));
  when(helper.streamCallSilent(any(),any(),any(),anyString())).thenReturn(new NodeStreamingChatHelper.StreamResult("malformed","",null,List.of(),false,7,3));
  var out=node.apply(new RunState(Map.of(PlanStateKeys.PLAN_ID,42L,PlanStateKeys.GOAL,"all stages",
    PlanStateKeys.COMPLETED_RESULTS,List.of(dishonest),MateClawStateKeys.PENDING_EVENTS,List.of(GraphEventPublisher.toolComplete("f","execute_code","missing language",false)))));
  assertEquals("incomplete",out.get(MateClawStateKeys.FINISH_REASON));
  assertFalse(String.valueOf(out.get(PlanStateKeys.FINAL_SUMMARY)).contains(dishonest));
  assertEquals(false,out.get(MateClawStateKeys.CONTENT_STREAMED));assertEquals(2,out.get(MateClawStateKeys.LLM_CALL_COUNT));
  assertEquals(17,out.get(MateClawStateKeys.PROMPT_TOKENS));verify(service,never()).completePlan(anyLong(),anyString());
  verify(helper).streamCallSilent(any(),any(),any(),anyString());
 }
 @Test void payloadRejectedSummaryCannotPublishDishonestStepRecoveryOrSpendCheckerCall() throws Exception {
  var helper=mock(NodeStreamingChatHelper.class);var service=mock(PlanningService.class);
  var node=new PlanSummaryNode(mock(ChatModel.class),service,helper);node.setFinalizationCoordinator(new TaskFinalizationCoordinator(helper,null));
  String dishonest="The failed print(1) was recovered by unrelated later calculations";
  when(helper.streamCallBufferedContent(any(),any(),any(),anyString())).thenReturn(new NodeStreamingChatHelper.StreamResult("","",null,List.of(),false,10,5,
    false,"HTTP 413",NodeStreamingChatHelper.ErrorType.PAYLOAD_TOO_LARGE));
  var state=new RunState(Map.of(PlanStateKeys.PLAN_ID,42L,PlanStateKeys.GOAL,"all stages",
    PlanStateKeys.COMPLETED_RESULTS,List.of(dishonest),MateClawStateKeys.PENDING_EVENTS,List.of(GraphEventPublisher.toolComplete("f","execute_code","missing language",false))));
  var out=node.apply(state);assertEquals("incomplete",out.get(MateClawStateKeys.FINISH_REASON));
  assertFalse(String.valueOf(out.get(PlanStateKeys.FINAL_SUMMARY)).contains(dishonest));
  assertTrue(String.valueOf(out.get(PlanStateKeys.FINAL_SUMMARY)).contains("HTTP 413"));
  assertEquals(List.of(dishonest),state.value(PlanStateKeys.COMPLETED_RESULTS,List.of()));
  assertEquals(1,out.get(MateClawStateKeys.LLM_CALL_COUNT));assertEquals(10,out.get(MateClawStateKeys.PROMPT_TOKENS));
  assertTrue(String.valueOf(out.get(MateClawStateKeys.PENDING_EVENTS)).contains("incomplete"));
  verify(helper,never()).streamCallSilent(any(),any(),any(),anyString());verify(service,never()).completePlan(anyLong(),anyString());
 }
 private static NodeStreamingChatHelper.StreamResult thinkingExhausted() {
  return new NodeStreamingChatHelper.StreamResult("","long reasoning",null,List.of(),false,10,5,true,"thinking_token_limit",NodeStreamingChatHelper.ErrorType.NONE);
 }
 @Test void thinkingExhaustedSummaryRetriesOnceWithThinkingOffAndLargerBudget() throws Exception {
  var helper=mock(NodeStreamingChatHelper.class);var service=mock(PlanningService.class);var model=mock(ChatModel.class);
  when(model.getDefaultOptions()).thenReturn(OpenAiChatOptions.builder().model("test").maxTokens(4096).build());
  var node=new PlanSummaryNode(model,service,helper);node.setFinalizationCoordinator(new TaskFinalizationCoordinator(helper,null));
  List<Integer> budgets=new ArrayList<>();List<String> thinking=new ArrayList<>();
  when(helper.streamCallBufferedContent(any(),any(),any(),anyString())).thenAnswer(call->{
    budgets.add(((Prompt)call.getArgument(1)).getOptions().getMaxTokens());
    thinking.add(ThinkingLevelHolder.get());
    return budgets.size()==1?thinkingExhausted()
      :new NodeStreamingChatHelper.StreamResult("today total 42","",new AssistantMessage("today total 42"),List.of(),false,10,5);
  });
  when(helper.streamCallSilent(any(),any(),any(),anyString())).thenReturn(new NodeStreamingChatHelper.StreamResult(
    "{\"decision\":\"ACCEPT\",\"taskComplete\":true,\"remainingWork\":false,\"failedCallCount\":0,\"recoveryClaims\":[],\"reason\":\"checked\"}","",null,List.of(),false,7,3));
  var out=node.apply(new RunState(Map.of(PlanStateKeys.PLAN_ID,42L,PlanStateKeys.GOAL,"daily stats",PlanStateKeys.COMPLETED_RESULTS,List.of("42"))));
  assertEquals("today total 42",out.get(PlanStateKeys.FINAL_SUMMARY));
  assertEquals(List.of(4096,8192),budgets);assertEquals("off",thinking.get(1));
  assertEquals(3,out.get(MateClawStateKeys.LLM_CALL_COUNT));verify(service).completePlan(42L,"today total 42");
 }
 @Test void repeatedOutputLimitReportsBudgetGuidanceWithoutCheckerCall() throws Exception {
  var helper=mock(NodeStreamingChatHelper.class);var service=mock(PlanningService.class);
  var node=new PlanSummaryNode(mock(ChatModel.class),service,helper);node.setFinalizationCoordinator(new TaskFinalizationCoordinator(helper,null));
  when(helper.streamCallBufferedContent(any(),any(),any(),anyString())).thenReturn(thinkingExhausted(),thinkingExhausted());
  var out=node.apply(new RunState(Map.of(PlanStateKeys.PLAN_ID,42L,PlanStateKeys.GOAL,"daily stats",PlanStateKeys.COMPLETED_RESULTS,List.of("42"))));
  assertEquals("incomplete",out.get(MateClawStateKeys.FINISH_REASON));
  String summary=String.valueOf(out.get(PlanStateKeys.FINAL_SUMMARY));
  assertTrue(summary.contains("max_tokens"));assertFalse(summary.contains("调用失败"));
  assertEquals(2,out.get(MateClawStateKeys.LLM_CALL_COUNT));
  verify(helper,times(2)).streamCallBufferedContent(any(),any(),any(),anyString());
  verify(helper,never()).streamCallSilent(any(),any(),any(),anyString());verify(service,never()).completePlan(anyLong(),anyString());
 }
 @Test void planReviewerUsesCurrentGoalWithoutRecalledMemoryEnvelope() throws Exception {
  var helper=mock(NodeStreamingChatHelper.class);var service=mock(PlanningService.class);
  var node=new PlanSummaryNode(mock(ChatModel.class),service,helper);node.setFinalizationCoordinator(new TaskFinalizationCoordinator(helper,null));
  String objective="Summarize current completed calculation 42",old="UNRELATED_OLD_TASK "+"old context ".repeat(300);
  when(helper.streamCallBufferedContent(any(),any(),any(),anyString())).thenReturn(new NodeStreamingChatHelper.StreamResult("Exact result 42","",new AssistantMessage("Exact result 42"),List.of(),false,10,5));
  when(helper.streamCallSilent(any(),any(),any(),anyString())).thenAnswer(call->{
    String data=((org.springframework.ai.chat.prompt.Prompt)call.getArgument(1)).getUserMessage().getText();
    assertTrue(data.contains("CURRENT OBJECTIVE:\n"+objective+"\nCANDIDATE:"));assertFalse(data.contains("UNRELATED_OLD_TASK"));
    return new NodeStreamingChatHelper.StreamResult("{\"decision\":\"ACCEPT\",\"taskComplete\":true,\"remainingWork\":false,\"failedCallCount\":0,\"recoveryClaims\":[],\"reason\":\"checked\"}","",null,List.of(),false,7,3);
  });
  var out=node.apply(new RunState(Map.of(PlanStateKeys.PLAN_ID,42L,PlanStateKeys.GOAL,"<memory-context>\n"+old+"\n</memory-context>\n\n"+objective,PlanStateKeys.COMPLETED_RESULTS,List.of("42"))));
  assertEquals("Exact result 42",out.get(PlanStateKeys.FINAL_SUMMARY));
 }
 @Test void verifierFailureCompletesPlanWithUnverifiedNotice() throws Exception {
  var helper=mock(NodeStreamingChatHelper.class);var service=mock(PlanningService.class);
  var node=new PlanSummaryNode(mock(ChatModel.class),service,helper);node.setFinalizationCoordinator(new TaskFinalizationCoordinator(helper,null));
  when(helper.streamCallBufferedContent(any(),any(),any(),anyString())).thenReturn(new NodeStreamingChatHelper.StreamResult("today total 42","",new AssistantMessage("today total 42"),List.of(),false,10,5));
  when(helper.streamCallSilent(any(),any(),any(),anyString())).thenReturn(new NodeStreamingChatHelper.StreamResult("","",null,List.of(),false,0,0,false,
    "LLM 调用失败: reset",NodeStreamingChatHelper.ErrorType.SERVER_ERROR));
  var out=node.apply(new RunState(Map.of(PlanStateKeys.PLAN_ID,42L,PlanStateKeys.GOAL,"daily stats",PlanStateKeys.COMPLETED_RESULTS,List.of("42"))));
  String summary=String.valueOf(out.get(PlanStateKeys.FINAL_SUMMARY));
  assertTrue(summary.startsWith("today total 42"));assertTrue(summary.contains("诊断码：CHECKER_ERROR"));
  assertNull(out.get(MateClawStateKeys.FINISH_REASON));
  verify(service).completePlan(42L,summary);verify(service,never()).markPlanFailed(anyLong(),anyString());
 }
}
