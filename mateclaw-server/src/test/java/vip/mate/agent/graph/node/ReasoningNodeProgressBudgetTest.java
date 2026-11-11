package vip.mate.agent.graph.node;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import vip.mate.agent.AgentToolSet;
import vip.mate.agent.GraphEventPublisher;
import vip.mate.agent.context.*;
import vip.mate.agent.graph.NodeStreamingChatHelper;
import vip.mate.agent.graph.state.ActionExecutionLedger;
import vip.mate.agent.progress.*;
import vip.mate.agent.runtime.state.RunState;
import vip.mate.tool.disclosure.ToolDisclosureService;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static vip.mate.agent.graph.state.MateClawStateKeys.*;
class ReasoningNodeProgressBudgetTest {
 @Test void authoritativeExecutionBudgetFactsAreInSystemPrefixAcrossPayloadRetry() throws Exception {
  for(int configuredCap:List.of(0,-1,7,Integer.MAX_VALUE)) {
   var helper=mock(NodeStreamingChatHelper.class);
   var rejected=new NodeStreamingChatHelper.StreamResult(null,"",null,List.of(),false,0,0,false,"HTTP 413",NodeStreamingChatHelper.ErrorType.PAYLOAD_TOO_LARGE);
   var answer=new NodeStreamingChatHelper.StreamResult("answer","",new AssistantMessage("answer"),List.of(),false,0,0);
   when(helper.streamCall(any(),any(),anyString(),anyString())).thenReturn(rejected,answer);
   var node=new ReasoningNode(mock(ChatModel.class),AgentToolSet.fromCallbacks(List.of(),List.of()),null,false,helper,null,null,1024,null,null,null,null);
   List<Message> history=new ArrayList<>();history.add(new UserMessage("ANCHOR"));
   for(int i=0;i<10;i++)history.add(new AssistantMessage("old history "+i));
   node.apply(new RunState(Map.of(CONVERSATION_ID,"conv",SYSTEM_PROMPT,"system",USER_MESSAGE,"ANCHOR",MESSAGES,history,MAX_ITERATIONS,configuredCap)));
   var captured=org.mockito.ArgumentCaptor.forClass(Prompt.class);
   verify(helper,times(2)).streamCall(any(),captured.capture(),anyString(),anyString());
   List<String> facts=new ArrayList<>();
   for(Prompt prompt:captured.getAllValues()) {
    String block=prompt.getInstructions().stream().filter(SystemMessage.class::isInstance)
      .map(Message::getText).filter(text->text.contains("Runtime execution budget facts")).findFirst().orElse("");
    assertTrue(block.contains("max_iterations="+configuredCap));
    assertTrue(block.contains(configuredCap<=0 ? "no configured iteration soft cap" : "configured iteration soft cap="+configuredCap));
    if(configuredCap<=0) assertTrue(block.contains("UNLIMITED"));
    assertTrue(block.contains("Context trimming and ledger record counts do not mean execution budget is exhausted"));
    assertTrue(block.contains("Context window and per-response output token limits are not whole-task iteration budgets"));
    assertTrue(block.contains("Only an explicit runtime termination signal"));
    assertTrue(block.contains("Current execution state: ACTIVE"));
    assertTrue(block.contains("This reasoning call has been admitted by the runtime; tools may continue"));
    assertTrue(block.contains("No runtime termination has been requested for this reasoning call"));
    assertTrue(block.contains("Do not self-terminate unfinished work merely because of a configured cap, history trimming, token limits, or ledger size"));
    assertTrue(block.contains("Cancellation and timeout protections still apply"));
    facts.add(block);
   }
   assertEquals(facts.get(0),facts.get(1),"recovery must preserve authoritative prefix");
  }
 }
 @Test void compressedRetryIsCheckedAgainstActualWindowBeforeProviderCall() throws Exception {
  var helper=mock(NodeStreamingChatHelper.class);var window=mock(ConversationWindowManager.class);
  when(window.compactAgedToolResponses(anyList(),anyInt())).thenAnswer(inv->inv.getArgument(0));
  when(window.pruneOldToolResultsForModelInput(anyList(),anyString(),anyString())).thenAnswer(inv->inv.getArgument(0));
  when(window.compactForRetry(anyList(),any(),anyString(),any())).thenReturn(List.of(new UserMessage("界".repeat(10000))));
  when(helper.streamCall(any(),any(),anyString(),anyString())).thenReturn(new NodeStreamingChatHelper.StreamResult(null,null,null,List.of(),false,0,0,false,"provider prompt too long",NodeStreamingChatHelper.ErrorType.PROMPT_TOO_LONG,false,0,0,0));
  var node=new ReasoningNode(mock(ChatModel.class),AgentToolSet.fromCallbacks(List.of(),List.of()),null,false,helper,window,null,1024,null,null,null,null);
  node.setPrefixBudgetPlan(new PrefixBudgetPlan(true,6000,PrefixBudgetPlan.Profile.MINIMAL,0,0,0,0,0,0,0));
  node.apply(new RunState(Map.of(CONVERSATION_ID,"conv",SYSTEM_PROMPT,"system",USER_MESSAGE,"ANCHOR",MESSAGES,List.of(new UserMessage("ANCHOR"),new AssistantMessage("recent")),MAX_ITERATIONS,100)));
  verify(helper,times(1)).streamCall(any(),any(),anyString(),anyString());
 }
 @Test void actualRemainingWindowBelowTwoThousandStillBoundsNormalHistory() throws Exception {
  assertPinnedWindowFits(20000, 40, 700);
 }
 @Test void fourHugePlainMessagesCannotOverrideRemainingWindow() throws Exception {
  assertPinnedWindowFits(15000, 4, 12000);
 }
 private void assertPinnedWindowFits(int pinChars, int historyCount, int messageChars) throws Exception {
  var helper=mock(NodeStreamingChatHelper.class);var service=mock(ProgressLedgerService.class);
  when(service.load("conv")).thenReturn(new ProgressLedger(Map.of(),Map.of("pin_skill",new ProgressEntry("pin_skill","skill",ProgressStatus.PENDING,"界".repeat(pinChars),Instant.now()))));
  when(helper.streamCall(any(),any(),anyString(),anyString())).thenReturn(new NodeStreamingChatHelper.StreamResult("partial","",new AssistantMessage("partial"),List.of(),false,0,0));
  var node=new ReasoningNode(mock(ChatModel.class),AgentToolSet.fromCallbacks(List.of(),List.of()),null,false,helper,null,null,1024,null,null,null,service);
  node.setPrefixBudgetPlan(new PrefixBudgetPlan(true,24000,PrefixBudgetPlan.Profile.NORMAL,0,0,0,0,0,0,0));
  List<Message> history=new ArrayList<>();history.add(new UserMessage("ANCHOR"));
  for(int i=0;i<historyCount;i++)history.add(new AssistantMessage("x".repeat(messageChars)));
  var original=List.copyOf(history);
  node.apply(new RunState(Map.of(CONVERSATION_ID,"conv",SYSTEM_PROMPT,"system",USER_MESSAGE,"ANCHOR",MESSAGES,history,MAX_ITERATIONS,100)));
  var cap=org.mockito.ArgumentCaptor.forClass(Prompt.class);verify(helper).streamCall(any(),cap.capture(),anyString(),anyString());
  assertEquals(original,history);
  assertTrue(cap.getValue().getInstructions().stream().anyMatch(m->m.getText().equals("ANCHOR")));
  assertTrue(TokenEstimator.estimateTokens(cap.getValue().getInstructions())+1024<=24000,
          "captured prompt estimate="+TokenEstimator.estimateTokens(cap.getValue().getInstructions()));
 }
 @Test void unfitLatestToolPairReturnsTypedErrorAfterOneCompactionAttempt() throws Exception {
  var helper=mock(NodeStreamingChatHelper.class);var service=mock(ProgressLedgerService.class);
  var window=mock(ConversationWindowManager.class);
  when(window.compactAgedToolResponses(anyList(),anyInt())).thenAnswer(inv->inv.getArgument(0));
  when(window.pruneOldToolResultsForModelInput(anyList(),anyString(),anyString())).thenAnswer(inv->inv.getArgument(0));
  when(helper.streamCall(any(),any(),anyString(),anyString())).thenReturn(new NodeStreamingChatHelper.StreamResult("unexpected","",new AssistantMessage("unexpected"),List.of(),false,0,0));
  when(service.load("conv")).thenReturn(new ProgressLedger(Map.of(),Map.of("pin_skill",new ProgressEntry("pin_skill","skill",ProgressStatus.PENDING,"界".repeat(15000),Instant.now()))));
  var node=new ReasoningNode(mock(ChatModel.class),AgentToolSet.fromCallbacks(List.of(),List.of()),null,false,helper,window,null,1024,null,null,null,service);
  node.setPrefixBudgetPlan(new PrefixBudgetPlan(true,24000,PrefixBudgetPlan.Profile.NORMAL,0,0,0,0,0,0,0));
  List<Message> history=List.of(new UserMessage("ANCHOR"),AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall("latest","function","execute_code","x".repeat(16000)))).build(),ToolResponseMessage.builder().responses(List.of(new ToolResponseMessage.ToolResponse("latest","execute_code","x".repeat(16000)))).build());
  var output=node.apply(new RunState(Map.of(CONVERSATION_ID,"conv",SYSTEM_PROMPT,"system",USER_MESSAGE,"ANCHOR",MESSAGES,history,MAX_ITERATIONS,100)));
  verify(helper,never()).streamCall(any(),any(),anyString(),anyString());
  verify(window).compactForRetry(anyList(),any(),anyString(),any());
  assertEquals("error_fallback",output.get(FINISH_REASON));
  assertTrue(output.get(FINAL_ANSWER).toString().contains("工具对"));
 }
 @Test void mandatoryOriginalObjectiveCannotBeSilentlyDroppedToFitWindow() throws Exception {
  var helper=mock(NodeStreamingChatHelper.class);
  when(helper.streamCall(any(),any(),anyString(),anyString())).thenReturn(new NodeStreamingChatHelper.StreamResult("unexpected","",new AssistantMessage("unexpected"),List.of(),false,0,0));
  var node=new ReasoningNode(mock(ChatModel.class),AgentToolSet.fromCallbacks(List.of(),List.of()),null,false,helper,null,null,1024,null,null,null,null);
  node.setPrefixBudgetPlan(new PrefixBudgetPlan(true,4096,PrefixBudgetPlan.Profile.MINIMAL,0,0,0,0,0,0,0));
  String objective="界".repeat(5000);
  node.apply(new RunState(Map.of(CONVERSATION_ID,"conv",SYSTEM_PROMPT,"system",USER_MESSAGE,objective,MESSAGES,List.of(new UserMessage(objective)),MAX_ITERATIONS,100)));
  verify(helper,never()).streamCall(any(),any(),anyString(),anyString());
 }
 @Test void completePinsReduceAvailableTailInsteadOfGrowingTotalPromptPastWindow() throws Exception {
  var helper=mock(NodeStreamingChatHelper.class);var service=mock(ProgressLedgerService.class);
  when(service.load("conv")).thenReturn(new ProgressLedger(Map.of(),Map.of("pin_skill",new ProgressEntry("pin_skill","skill",ProgressStatus.PENDING,"界".repeat(15000),Instant.now()))));
  when(helper.streamCall(any(),any(),anyString(),anyString())).thenReturn(new NodeStreamingChatHelper.StreamResult("partial","",new AssistantMessage("partial"),List.of(),false,0,0));
  var node=new ReasoningNode(mock(ChatModel.class),AgentToolSet.fromCallbacks(List.of(),List.of()),null,false,helper,null,null,1024,null,null,null,service);
  node.setPrefixBudgetPlan(new PrefixBudgetPlan(true,24000,PrefixBudgetPlan.Profile.NORMAL,0,0,0,0,0,0,0));
  List<Message> history=new ArrayList<>();history.add(new UserMessage("ANCHOR"));
  for(int i=0;i<40;i++)history.add(new AssistantMessage("x".repeat(700)));
  node.apply(new RunState(Map.of(CONVERSATION_ID,"conv",SYSTEM_PROMPT,"system",USER_MESSAGE,"ANCHOR",MESSAGES,history,MAX_ITERATIONS,100)));
  var cap=org.mockito.ArgumentCaptor.forClass(Prompt.class);verify(helper).streamCall(any(),cap.capture(),anyString(),anyString());
  assertTrue(cap.getValue().getInstructions().stream().anyMatch(m->m.getText().contains("界".repeat(15000))));
  assertTrue(TokenEstimator.estimateTokens(cap.getValue().getInstructions())+1024<=24000);
 }
 @Test void impossibleCompletePinsFailFastWithoutTryingHistoryCompaction() throws Exception {
  var helper=mock(NodeStreamingChatHelper.class);var service=mock(ProgressLedgerService.class);
  var window=mock(ConversationWindowManager.class);
  when(helper.streamCall(any(),any(),anyString(),anyString())).thenReturn(new NodeStreamingChatHelper.StreamResult("unexpected retry","",new AssistantMessage("unexpected retry"),List.of(),false,0,0));
  when(window.compactAgedToolResponses(anyList(),anyInt())).thenAnswer(inv->inv.getArgument(0));
  when(window.pruneOldToolResultsForModelInput(anyList(),anyString(),anyString())).thenAnswer(inv->inv.getArgument(0));
  when(window.compactForRetry(anyList(),any(),anyString(),any())).thenReturn(List.of());
  when(service.load("conv")).thenReturn(new ProgressLedger(Map.of(),Map.of("pin_skill",new ProgressEntry("pin_skill","skill",ProgressStatus.PENDING,"界".repeat(10000),Instant.now()))));
  var node=new ReasoningNode(mock(ChatModel.class),AgentToolSet.fromCallbacks(List.of(),List.of()),null,false,helper,window,null,1024,null,null,null,service);
  node.setPrefixBudgetPlan(new PrefixBudgetPlan(true,4096,PrefixBudgetPlan.Profile.MINIMAL,0,0,0,0,0,0,0));
  node.apply(new RunState(Map.of(CONVERSATION_ID,"conv",SYSTEM_PROMPT,"system",USER_MESSAGE,"task",MESSAGES,List.of(new UserMessage("task")),MAX_ITERATIONS,100)));
  verify(helper,never()).streamCall(any(),any(),anyString(),anyString());
  verify(window,never()).compactForRetry(anyList(),any(),anyString(),any());
 }
 @Test void actualCapturedPromptBoundsLedgerPreservesReceiptsAnchorPairsAndHistory() throws Exception {
  var helper=mock(NodeStreamingChatHelper.class);var service=mock(ProgressLedgerService.class);
  var deferred=mock(ToolCallback.class);
  when(deferred.getToolDefinition()).thenReturn(ToolDefinition.builder().name("big_deferred").description("x".repeat(200000)).inputSchema("{}").build());
  var tools=AgentToolSet.fromCallbacks(List.of(),List.of(deferred));
  var disclosure=mock(ToolDisclosureService.class);
  when(disclosure.split(any(),anySet(),anySet())).thenReturn(new ToolDisclosureService.ToolDisclosureSplit(List.of(),List.of(deferred)));
  var entries=new LinkedHashMap<String,ProgressEntry>();
  for(int i=0;i<1000;i++) entries.put("step"+i,new ProgressEntry("step"+i,"L".repeat(1000),ProgressStatus.DONE,"N".repeat(10000),Instant.now()));
  entries.put("pending",new ProgressEntry("pending","current work",ProgressStatus.PENDING,"keep",Instant.now()));
  when(service.load("conv")).thenReturn(new ProgressLedger(entries));
  when(helper.streamCall(any(),any(),anyString(),anyString())).thenReturn(new NodeStreamingChatHelper.StreamResult("result","",new AssistantMessage("result"),List.of(),false,1,1));
  var node=new ReasoningNode(mock(ChatModel.class),tools,null,false,helper,null,null,1024,null,null,disclosure,service);
  node.setPrefixBudgetPlan(new PrefixBudgetPlan(true,24000,PrefixBudgetPlan.Profile.COMPACT,2000,0,0,0,0,600,1000));
  List<Message> history=new ArrayList<>();history.add(new UserMessage("ANCHORED_USER_REQUEST"));
  for(int i=0;i<100;i++) {
   String id="id"+i;
   history.add(AssistantMessage.builder().content("thought".repeat(1000)).toolCalls(List.of(new AssistantMessage.ToolCall(id,"function","execute_code","{}"))).build());
   history.add(ToolResponseMessage.builder().responses(List.of(new ToolResponseMessage.ToolResponse(id,"execute_code","x".repeat(1500)))).build());
  }
  var original=List.copyOf(history);
  var receipts=new ArrayList<GraphEventPublisher.GraphEvent>();
  for(int i=0;i<24;i++)receipts.add(GraphEventPublisher.toolComplete("r"+i,"execute_code","numeric11984445550"+"z".repeat(480),true));
  node.apply(new RunState(Map.of(CONVERSATION_ID,"conv",SYSTEM_PROMPT,"system",USER_MESSAGE,"ANCHORED_USER_REQUEST",MESSAGES,history,MAX_ITERATIONS,100,ACTION_EXECUTION_LEDGER,ActionExecutionLedger.fromEvents(receipts))));
  var cap=org.mockito.ArgumentCaptor.forClass(Prompt.class);verify(helper).streamCall(any(),cap.capture(),anyString(),anyString());
  var prompt=cap.getValue().getInstructions();
  assertEquals(original,history,"input state history must never be mutated");
  assertTrue(prompt.stream().anyMatch(m->m.getText().equals("ANCHORED_USER_REQUEST")));
  assertTrue(prompt.stream().anyMatch(m->m.getText().contains("11984445550")));
  var snapshot=prompt.stream().filter(m->m.getText().contains("Progress data:")).findFirst().orElseThrow();
  assertTrue(TokenEstimator.estimateTokens(snapshot.getText())<=600);
  assertTrue(snapshot.getText().contains("pending"));assertFalse(snapshot.getText().contains("step999"));
  assertTrue(TokenEstimator.estimateTokens(prompt)+1024<24000);
  assertTrue(prompt.size()<history.size());
  var calls=new HashSet<String>(); var results=new HashSet<String>();
  for(Message m:prompt) {
   if(m instanceof AssistantMessage a) for(var call:a.getToolCalls())calls.add(call.id());
   if(m instanceof ToolResponseMessage t) for(var result:t.getResponses())results.add(result.id());
  }
  assertEquals(calls,results);
 }
}
