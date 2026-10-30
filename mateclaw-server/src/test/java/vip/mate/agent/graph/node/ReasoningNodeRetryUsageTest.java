package vip.mate.agent.graph.node;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import vip.mate.agent.AgentToolSet;
import vip.mate.agent.GraphEventPublisher;
import vip.mate.agent.context.*;
import vip.mate.agent.graph.NodeStreamingChatHelper;
import vip.mate.agent.graph.guard.TaskFinalizationCoordinator;
import vip.mate.agent.graph.state.ActionExecutionLedger;
import vip.mate.agent.runtime.state.RunState;
import java.util.*;
import java.util.concurrent.CancellationException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static vip.mate.agent.graph.state.MateClawStateKeys.*;

/** Every returned logical call contributes usage exactly once, including rejected attempts. */
class ReasoningNodeRetryUsageTest {
    private final NodeStreamingChatHelper helper=mock(NodeStreamingChatHelper.class);
    private final ChatModel model=mock(ChatModel.class);
    private static final String ACCEPT="{\"decision\":\"ACCEPT\",\"taskComplete\":true,\"remainingWork\":false,\"failedCallCount\":0,\"recoveryClaims\":[],\"reason\":\"checked\"}";
    private static NodeStreamingChatHelper.StreamResult response(String text,String thinking,
            NodeStreamingChatHelper.ErrorType error,int unit) {
        return new NodeStreamingChatHelper.StreamResult(text,thinking,new AssistantMessage(text==null?"":text),List.of(),false,
                11*unit,7*unit,false,error==NodeStreamingChatHelper.ErrorType.NONE?null:"provider rejected request",error,false,5*unit,3*unit,2*unit);
    }
    private static NodeStreamingChatHelper.StreamResult answer(int unit){return response("Exact result 42","",NodeStreamingChatHelper.ErrorType.NONE,unit);}
    private ReasoningNode node(ConversationWindowManager window) {
        var node=new ReasoningNode(model,AgentToolSet.fromCallbacks(List.of(),List.of()),null,false,
                helper,window,null,1024,null,null,null,null);
        node.setFinalizationCoordinator(new TaskFinalizationCoordinator(helper,null));
        node.setPrefixBudgetPlan(PrefixBudgetPlan.unlimited(24000));
        when(helper.streamCallSilent(any(),any(),anyString(),anyString())).thenReturn(response(ACCEPT,"",NodeStreamingChatHelper.ErrorType.NONE,8));
        return node;
    }
    private RunState state() {
        var values=new HashMap<String,Object>();
        values.put(CONVERSATION_ID,"retry-usage");values.put(SYSTEM_PROMPT,"Help");values.put(USER_MESSAGE,"Compute result");
        var history=new ArrayList<Message>();history.add(new UserMessage("Compute result"));
        for(int i=0;i<20;i++)history.add(new AssistantMessage("old history "+"x".repeat(500)));
        values.put(MESSAGES,history);values.put(MAX_ITERATIONS,100);values.put(LLM_CALL_COUNT,7);
        values.put(PROMPT_TOKENS,1000);values.put(COMPLETION_TOKENS,500);values.put(CACHE_READ_TOKENS,200);
        values.put(CACHE_WRITE_TOKENS,100);values.put(REASONING_TOKENS,50);
        values.put(ACTION_EXECUTION_LEDGER,ActionExecutionLedger.fromEvents(List.of(GraphEventPublisher.toolComplete("s","calculate","42",true))));
        return new RunState(values);
    }
    private void usage(Map<String,Object> output,int units,int calls) {
        assertEquals(1000+11*units,output.get(PROMPT_TOKENS));assertEquals(500+7*units,output.get(COMPLETION_TOKENS));
        assertEquals(200+5*units,output.get(CACHE_READ_TOKENS));assertEquals(100+3*units,output.get(CACHE_WRITE_TOKENS));
        assertEquals(50+2*units,output.get(REASONING_TOKENS));assertEquals(calls,output.get(LLM_CALL_COUNT));
    }
    private ConversationWindowManager window() {
        var window=mock(ConversationWindowManager.class);
        when(window.compactAgedToolResponses(anyList(),anyInt())).thenAnswer(call->call.getArgument(0));
        when(window.pruneOldToolResultsForModelInput(anyList(),anyString(),anyString())).thenAnswer(call->call.getArgument(0));
        return window;
    }
    @Test void blankAndThinkingRetriesPlusCheckerAccumulateAllFiveCountersExactlyOnce() throws Exception {
        when(helper.streamCallBufferedContent(any(),any(),anyString(),anyString())).thenReturn(
                response("","",NodeStreamingChatHelper.ErrorType.NONE,1),
                response("","thinking",NodeStreamingChatHelper.ErrorType.NONE,2),answer(4));
        var output=node(null).apply(state());usage(output,15,11);assertEquals("normal",output.get(FINISH_REASON));
    }
    @Test void payloadRetryAndCheckerAccumulateReturnedUsage() throws Exception {
        when(helper.streamCallBufferedContent(any(),any(),anyString(),anyString())).thenReturn(
                response(null,"",NodeStreamingChatHelper.ErrorType.PAYLOAD_TOO_LARGE,1),answer(2));
        var output=node(null).apply(state());usage(output,11,10);assertEquals("normal",output.get(FINISH_REASON));
    }
    @Test void contextRetryAndSubsequentEmptyRetryPreserveAllCandidateUsage() throws Exception {
        var window=window();
        when(window.compactForRetry(anyList(),any(),anyString(),any())).thenReturn(List.of(new UserMessage("Compute result"),new AssistantMessage("compact")));
        when(helper.streamCallBufferedContent(any(),any(),anyString(),anyString())).thenReturn(
                response(null,"",NodeStreamingChatHelper.ErrorType.PROMPT_TOO_LONG,1),
                response("","",NodeStreamingChatHelper.ErrorType.NONE,2),answer(4));
        var output=node(window).apply(state());usage(output,15,11);assertEquals("normal",output.get(FINISH_REASON));
    }
    @Test void sameCountCompactionRetriesWhenTokenEstimateDrops() throws Exception {
        var window = window();
        when(window.compactForRetry(anyList(), any(), anyString(), any())).thenAnswer(call -> {
            List<Message> history = call.getArgument(0);
            return history.stream().map(message -> message instanceof AssistantMessage
                    ? (Message) new AssistantMessage("compact") : message).toList();
        });
        when(helper.streamCallBufferedContent(any(), any(), anyString(), anyString())).thenReturn(
                response(null, "", NodeStreamingChatHelper.ErrorType.PROMPT_TOO_LONG, 1), answer(2));
        var output = node(window).apply(state());
        usage(output, 11, 10);
        assertEquals("normal", output.get(FINISH_REASON));
        verify(helper).streamCallBufferedContent(any(), any(), anyString(), eq("reasoning_compact_retry"));
    }
    @Test void cancellationAfterMultipleReturnedCandidatesRetainsTheirUsage() throws Exception {
        when(helper.streamCallBufferedContent(any(),any(),anyString(),anyString())).thenReturn(
                response("","",NodeStreamingChatHelper.ErrorType.NONE,1),
                response("","thinking",NodeStreamingChatHelper.ErrorType.NONE,2)).thenThrow(new CancellationException("stop"));
        var output=node(null).apply(state());usage(output,3,10);assertEquals("stopped",output.get(FINISH_REASON));
        verify(helper,never()).streamCallSilent(any(),any(),anyString(),anyString());
    }
    @Test void rejectedCheckerStillCountsAllCandidateAndCheckerUsage() throws Exception {
        var node=node(null);
        when(helper.streamCallBufferedContent(any(),any(),anyString(),anyString())).thenReturn(
                response("","",NodeStreamingChatHelper.ErrorType.NONE,1),answer(2));
        when(helper.streamCallSilent(any(),any(),anyString(),anyString())).thenReturn(response("malformed","",NodeStreamingChatHelper.ErrorType.NONE,8));
        var output=node.apply(state());usage(output,11,10);assertEquals("incomplete",output.get(FINISH_REASON));
    }
    @Test void localCompactionRejectionPreservesIncurredCandidateUsageWithoutInventingCall() throws Exception {
        var window=window();
        when(helper.streamCallBufferedContent(any(),any(),anyString(),anyString())).thenReturn(response(null,"",NodeStreamingChatHelper.ErrorType.PROMPT_TOO_LONG,1));
        when(window.compactForRetry(anyList(),any(),anyString(),any())).thenAnswer(call->{
            ((ChatModel)call.getArgument(1)).call(new Prompt("界".repeat(40000)));
            return List.of(new UserMessage("Compute result"));
        });
        var output=node(window).apply(state());usage(output,1,8);assertEquals("incomplete",output.get(FINISH_REASON));
        verify(model,never()).call(any(Prompt.class));
    }
    @Test void summaryUsageAndAllReturnedCandidateCountersMergeOnceOnSuccessAndCancellation() throws Exception {
        for(boolean cancel:List.of(false,true)) {
            reset(helper,model);
            var window=window();var node=node(window);
            var summaryNative=new vip.mate.llm.dashscope.NativeDashScopeUsage(33,21,54,15L,9L,
                    new vip.mate.llm.dashscope.NativeDashScopeUsage.CompletionTokenDetails(6),Map.of());
            when(model.call(any(Prompt.class))).thenReturn(new org.springframework.ai.chat.model.ChatResponse(
                    List.of(new org.springframework.ai.chat.model.Generation(new AssistantMessage("summary"))),
                    org.springframework.ai.chat.metadata.ChatResponseMetadata.builder().usage(
                            new org.springframework.ai.chat.metadata.DefaultUsage(33,21,54,summaryNative,15L,9L)).build()));
            when(window.compactForRetry(anyList(),any(),anyString(),any())).thenAnswer(call->{
                ((ChatModel)call.getArgument(1)).call(new Prompt("summary"));
                return List.of(new UserMessage("Compute result"),new AssistantMessage("compact"));
            });
            var calls=when(helper.streamCallBufferedContent(any(),any(),anyString(),anyString()))
                    .thenReturn(response(null,"",NodeStreamingChatHelper.ErrorType.PROMPT_TOO_LONG,1));
            if(cancel)calls.thenThrow(new CancellationException("stop"));else calls.thenReturn(answer(2));
            var output=node.apply(state());usage(output,cancel?4:14,cancel?10:11);
            assertEquals(cancel?"stopped":"normal",output.get(FINISH_REASON));
        }
    }
    @Test void localNudgeOverflowAfterReturnedBlankKeepsItsUsage() throws Exception {
        var node=node(null);
        when(helper.streamCallBufferedContent(any(),any(),anyString(),anyString())).thenAnswer(call->{
            node.setPrefixBudgetPlan(PrefixBudgetPlan.unlimited(1024));
            return response("","",NodeStreamingChatHelper.ErrorType.NONE,1);
        });
        var output=node.apply(state());usage(output,1,8);assertEquals("incomplete",output.get(FINISH_REASON));
        verify(helper,times(1)).streamCallBufferedContent(any(),any(),anyString(),anyString());
    }
    @Test void budgetStopAfterTwoReturnedBlankAttemptsPreservesBothWithoutChecker() throws Exception {
        var values=state();values.replace(MAX_ITERATIONS,2);values.replace(LLM_CALL_COUNT,8);
        when(helper.streamCallBufferedContent(any(),any(),anyString(),anyString())).thenReturn(
                response("","",NodeStreamingChatHelper.ErrorType.NONE,1),response("","thinking",NodeStreamingChatHelper.ErrorType.NONE,2));
        var output=node(null).apply(values);usage(output,3,10);assertEquals("incomplete",output.get(FINISH_REASON));
        verify(helper,never()).streamCallSilent(any(),any(),anyString(),anyString());
    }
}
