package vip.mate.agent.graph.node;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import vip.mate.agent.AgentToolSet;
import vip.mate.agent.GraphEventPublisher;
import vip.mate.agent.graph.NodeStreamingChatHelper;
import vip.mate.agent.graph.guard.TaskFinalizationCoordinator;
import vip.mate.agent.graph.state.*;
import vip.mate.agent.runtime.state.RunState;
import vip.mate.channel.web.ChatStreamTracker;

import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static vip.mate.agent.graph.state.MateClawStateKeys.*;

class ReasoningNodeWikiEvidenceTest {
    private static final String WIKI = "{\"chunks\":[{\"index\":1,\"chunkId\":101,\"rawTitle\":\"员工手册\",\"snippet\":\"年假为15天；由LeaveManager管理审批\"}]}";
    private static ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
    private static RunState state() {
        var ledger = SourceEvidenceLedger.fromToolResponses(List.of(new ToolResponseMessage.ToolResponse("wiki-1","wiki_semantic_search",WIKI)));
        return new RunState(Map.of(CONVERSATION_ID,"wiki-686", SYSTEM_PROMPT,"根据知识库回答", USER_MESSAGE,"年假多少天？", MESSAGES,List.of(new UserMessage("年假多少天？")),
                MAX_ITERATIONS,10, SOURCE_EVIDENCE_LEDGER,ledger,
                ACTION_EXECUTION_LEDGER,ActionExecutionLedger.fromEvents(List.of(GraphEventPublisher.toolComplete("wiki-1","wiki_semantic_search",WIKI,true)))));
    }
    private static ReasoningNode node(ChatModel model, ChatStreamTracker tracker) {
        var helper = new NodeStreamingChatHelper(tracker);
        var node = new ReasoningNode(model,AgentToolSet.fromCallbacks(List.of(),List.of()),null,helper,null,tracker);
        node.setFinalizationCoordinator(new TaskFinalizationCoordinator(helper,tracker));
        return node;
    }
    private static String accept() {
        return "{\"decision\":\"ACCEPT\",\"taskComplete\":true,\"remainingWork\":false,\"failedCallCount\":0,\"recoveryClaims\":[],\"reason\":\"Matches wiki evidence\"}";
    }
    @Test void exhaustedCitationRepairPreservesBufferedAnswerAndWarning() throws Exception {
        var model=mock(ChatModel.class);
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(response("员工年假为15天。")),Flux.just(response(accept())));
        var tracker=new ChatStreamTracker(new ObjectMapper());
        try {
            tracker.register("wiki-686");
            var state=state();state.replace(FINALIZATION_ATTEMPTS,2);
            var out=node(model,tracker).apply(state);
            assertEquals("evidence_insufficient",out.get(FINISH_REASON));
            assertTrue(out.get(FINAL_ANSWER).toString().contains("员工年假为15天。"),"Buffered candidate must not be replaced by a warning");
            assertTrue(out.get(FINAL_ANSWER).toString().contains("证据不足"));
            assertEquals("",out.get(STREAMED_CONTENT));
        } finally {tracker.onShutdown();}
    }
    @Test void missingWikiCitationGetsBoundedRepairBeforePublication() throws Exception {
        var model=mock(ChatModel.class);
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(response("员工年假为15天。")),
                Flux.just(response("员工年假为15天 [1]。")),Flux.just(response(accept())));
        var tracker=new ChatStreamTracker(new ObjectMapper());
        try {
            tracker.register("wiki-686");var state=state();var node=node(model,tracker);
            var first=node.apply(state);
            assertEquals(true,first.get(CONTINUE_REASONING));
            assertEquals("",first.get(FINAL_ANSWER));
            assertEquals(1,first.get(FINALIZATION_ATTEMPTS));
            state.applyPatch(first);
            var second=node.apply(state);
            assertEquals("normal",second.get(FINISH_REASON));
            assertTrue(second.get(FINAL_ANSWER).toString().contains("员工年假为15天 [1]"));
            assertTrue(second.get(FINAL_ANSWER).toString().contains("[1] 员工手册"));
            assertEquals(3,second.get(LLM_CALL_COUNT));
        } finally {tracker.onShutdown();}
    }
    @Test void wikiOnlyEvidenceDoesNotApplyJavaSourceSymbolPolicy() {
        var ledger=new MateClawStateAccessor(state()).sourceEvidenceLedger();
        var answer=ledger.appendWikiSourceTable("年假15天，由LeaveManager管理审批 [1]。");
        assertTrue(ledger.validateAnswer(answer).valid());
        var source=ledger.withSourcePath("src/LeaveManager.java");
        assertFalse(source.validateAnswer(source.appendWikiSourceTable("由InventedService管理 [1]。")).valid());
    }
    @Test void ordinaryFileEvidenceRetainsStrictSourceValidation() {
        var ledger=SourceEvidenceLedger.empty().withSourcePath("README.md");
        assertFalse(ledger.validateAnswer("InventedService.java handles requests.").valid());
    }

}
