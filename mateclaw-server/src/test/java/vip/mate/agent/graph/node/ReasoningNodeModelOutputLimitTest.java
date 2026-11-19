package vip.mate.agent.graph.node;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import vip.mate.agent.AgentToolSet;
import vip.mate.agent.graph.NodeStreamingChatHelper;
import vip.mate.agent.runtime.state.RunState;
import vip.mate.channel.web.ChatStreamTracker;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static vip.mate.agent.graph.state.MateClawStateKeys.*;

class ReasoningNodeModelOutputLimitTest {
    @ParameterizedTest @ValueSource(strings={"length", "max_tokens"})
    void truncatedAnswerIsIncompleteWithoutLosingTextOrRetrying(String reason) throws Exception {
        var model = mock(ChatModel.class);
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(new ChatResponse(List.of(new Generation(
                new AssistantMessage("Partial answer with useful evidence"),
                ChatGenerationMetadata.builder().finishReason(reason).build())))));
        var node = new ReasoningNode(model, AgentToolSet.fromCallbacks(List.of(),List.of()), null,false,
                new NodeStreamingChatHelper(mock(ChatStreamTracker.class)),null,null,1024,null,null,null,null);
        var output = node.apply(new RunState(Map.of(CONVERSATION_ID,"output-limit-test", USER_MESSAGE,"Explain", SYSTEM_PROMPT,"Help",
                MESSAGES,List.of(new UserMessage("Explain")), MAX_ITERATIONS,10)));
        assertEquals("incomplete",output.get(FINISH_REASON));
        assertEquals("Partial answer with useful evidence",output.get(FINAL_ANSWER));
        assertEquals(false,output.get(NEEDS_TOOL_CALL));
        assertEquals(1,output.get(LLM_CALL_COUNT));
        verify(model,times(1)).stream(any(Prompt.class)); verify(model,never()).call(any(Prompt.class));
        var finalState = new java.util.HashMap<String,Object>(); finalState.putAll(output);
        var finalized = new FinalAnswerNode().apply(new RunState(finalState));
        assertEquals("incomplete", finalized.get(FINISH_REASON));
        assertEquals("Partial answer with useful evidence",finalized.get(FINAL_ANSWER));
    }
    @org.junit.jupiter.api.Test
    void ordinaryStopRemainsNormal() throws Exception {
        var model = mock(ChatModel.class);
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(new ChatResponse(List.of(new Generation(
                new AssistantMessage("Complete answer"), ChatGenerationMetadata.builder().finishReason("stop").build())))));
        var node = new ReasoningNode(model, AgentToolSet.fromCallbacks(List.of(),List.of()), null,false,
                new NodeStreamingChatHelper(mock(ChatStreamTracker.class)),null,null,1024,null,null,null,null);
        var output = node.apply(new RunState(Map.of(CONVERSATION_ID,"normal-stop-test", USER_MESSAGE,"Explain", SYSTEM_PROMPT,"Help",
                MESSAGES,List.of(new UserMessage("Explain")), MAX_ITERATIONS,10)));
        assertEquals("normal",output.get(FINISH_REASON));
        assertEquals("Complete answer",output.get(FINAL_ANSWER));
    }
}
