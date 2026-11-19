package vip.mate.agent.graph.node;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import vip.mate.agent.AgentToolSet;
import vip.mate.agent.GraphEventPublisher;
import vip.mate.agent.graph.NodeStreamingChatHelper;
import vip.mate.agent.graph.state.ActionExecutionLedger;
import vip.mate.agent.runtime.state.RunState;
import vip.mate.channel.web.ChatStreamTracker;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static vip.mate.agent.graph.state.MateClawStateKeys.*;

class ReasoningNodeExecutionBudgetTest {
    static ChatResponse proposal() {
        var message=AssistantMessage.builder().content("").toolCalls(List.of(
            new AssistantMessage.ToolCall("proposed","function","execute_code","{\"language\":\"python\",\"code\":\"print(open('sales.csv').read()[:100])\"}"))).build();
        return new ChatResponse(List.of(new Generation(message)));
    }
    static RunState state(String objective, ActionExecutionLedger ledger) {
        return new RunState(Map.of(CONVERSATION_ID,"budget-test",SYSTEM_PROMPT,"Help",USER_MESSAGE,objective,
            MESSAGES,List.of(),MAX_ITERATIONS,8,CURRENT_ITERATION,0,ACTION_EXECUTION_LEDGER,ledger));
    }
    static ReasoningNode node(ChatModel model) {
        var tools=mock(AgentToolSet.class);when(tools.callbacks()).thenReturn(List.of());
        var tracker=mock(ChatStreamTracker.class);
        return new ReasoningNode(model,tools,null,new NodeStreamingChatHelper(tracker),null,tracker);
    }
    @Test void lastExecutionSlotIsReviewedBeforeDispatchingProbeOnlyCode() throws Exception {
        var model=mock(ChatModel.class);
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(proposal()),Flux.just(new ChatResponse(List.of(
            new Generation(new AssistantMessage("{\"decision\":\"REPLAN\",\"reason\":\"Only probes input; SELECT aggregation is still missing\"}"))))));
        var output=node(model).apply(state("只调用一次 execute_code，用sqlite3完成SELECT聚合并报告总数。",ActionExecutionLedger.empty()));
        assertEquals(true,output.get(CONTINUE_REASONING));
        assertEquals(false,output.get(NEEDS_TOOL_CALL));
        assertTrue(((List<?>)output.get(TOOL_CALLS)).isEmpty());
        assertEquals(2,output.get(LLM_CALL_COUNT));
        for(Object value:(List<?>)output.get(MESSAGES))
            if(value instanceof AssistantMessage message)assertFalse(message.hasToolCalls(),"Undispatched proposals must not create unmatched tool calls");
        verify(model,times(2)).stream(any(Prompt.class));
    }
    @Test void exhaustedExplicitBudgetCannotDispatchAnotherExecution() throws Exception {
        var ledger=ActionExecutionLedger.fromEvents(List.of(GraphEventPublisher.toolComplete("used","execute_code","probe succeeded",true)));
        var model=mock(ChatModel.class);when(model.stream(any(Prompt.class))).thenReturn(Flux.just(proposal()));
        var output=node(model).apply(state("只调用一次 execute_code，完成聚合。",ledger));
        assertEquals(false,output.get(NEEDS_TOOL_CALL));
        assertEquals("incomplete",output.get(FINISH_REASON));
        verify(model).stream(any(Prompt.class));
    }
    @Test void noExplicitBudgetKeepsExistingExecutionPath() throws Exception {
        var model=mock(ChatModel.class);when(model.stream(any(Prompt.class))).thenReturn(Flux.just(proposal()));
        var output=node(model).apply(state("读取sales.csv并完成聚合。",ActionExecutionLedger.empty()));
        assertEquals(true,output.get(NEEDS_TOOL_CALL));
        verify(model).stream(any(Prompt.class));
    }
}
