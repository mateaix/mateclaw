package vip.mate.agent.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import vip.mate.agent.AgentToolSet;
import vip.mate.agent.graph.NodeStreamingChatHelper;
import vip.mate.agent.graph.state.SourceEvidenceLedger;
import vip.mate.channel.web.ChatStreamTracker;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static vip.mate.agent.graph.state.MateClawStateKeys.*;

/**
 * ReasoningNode 输出 map 断言测试。
 * <p>
 * 验证每条退出路径的输出 map 都包含 needsToolCall + shouldSummarize 的显式值，
 * 防止 stale-state 导致 ReasoningDispatcher 误路由。
 */
class ReasoningNodeOutputTest {

    private ChatModel chatModel;
    private NodeStreamingChatHelper streamingHelper;
    private ChatStreamTracker streamTracker;
    private AgentToolSet toolSet;

    @BeforeEach
    void setUp() {
        chatModel = mock(ChatModel.class);
        streamingHelper = mock(NodeStreamingChatHelper.class);
        streamTracker = mock(ChatStreamTracker.class);
        toolSet = mock(AgentToolSet.class);
        when(toolSet.callbacks()).thenReturn(List.of());
        when(streamTracker.isStopRequested(anyString())).thenReturn(false);
    }

    private ReasoningNode createNode() {
        return new ReasoningNode(chatModel, toolSet, null, streamingHelper, null, streamTracker);
    }

    /** 构建包含前一轮残留标志的 stale state */
    private OverAllState buildStaleState() {
        Map<String, Object> map = new HashMap<>();
        map.put(CONVERSATION_ID, "test-conv");
        map.put(SYSTEM_PROMPT, "you are a helper");
        map.put(USER_MESSAGE, "hello");
        map.put(MESSAGES, List.of());
        map.put(CURRENT_ITERATION, 3);
        map.put(MAX_ITERATIONS, 10);
        map.put(LLM_CALL_COUNT, 5);
        map.put(FORCED_TOOL_CALL, "");
        // 前一轮残留标志
        map.put(NEEDS_TOOL_CALL, true);
        map.put(SHOULD_SUMMARIZE, true);
        return new OverAllState(map);
    }

    // ===== 控制流标志断言辅助 =====

    private void assertControlFlagsCleared(Map<String, Object> output, String path) {
        assertEquals(false, output.get(NEEDS_TOOL_CALL),
                path + ": needsToolCall should be explicitly false");
        assertEquals(false, output.get(SHOULD_SUMMARIZE),
                path + ": shouldSummarize should be explicitly false");
    }

    private void assertLlmCallCountWritten(Map<String, Object> output, String path) {
        assertNotNull(output.get(LLM_CALL_COUNT),
                path + ": llmCallCount should be written");
        assertTrue((int) output.get(LLM_CALL_COUNT) > 0,
                path + ": llmCallCount should be positive");
    }

    // ===== 正常 final answer =====

    @Test
    @DisplayName("正常 final answer 路径：output 包含 needsToolCall=false + shouldSummarize=false")
    void normalFinalAnswer_clearsControlFlags() throws Exception {
        NodeStreamingChatHelper.StreamResult result = new NodeStreamingChatHelper.StreamResult(
                "回答内容", "", new AssistantMessage("回答内容"),
                List.of(), false, 100, 50);
        when(streamingHelper.streamCall(any(), any(), anyString(), anyString())).thenReturn(result);

        Map<String, Object> output = createNode().apply(buildStaleState());

        assertControlFlagsCleared(output, "normalFinalAnswer");
        assertLlmCallCountWritten(output, "normalFinalAnswer");
        assertEquals("回答内容", output.get(FINAL_ANSWER));
    }

    @Test
    @DisplayName("源码证据不足的 final answer：原文进 streamedContent，警告作为 finalAnswer 追加")
    void evidenceInsufficientFinalAnswer_splitsPersistedContentAndWarning() throws Exception {
        NodeStreamingChatHelper.StreamResult result = new NodeStreamingChatHelper.StreamResult(
                "SkillController.java 是入口，SkillServiceImpl.java 负责业务。", "",
                new AssistantMessage("SkillController.java 是入口，SkillServiceImpl.java 负责业务。"),
                List.of(), false, 100, 50);
        when(streamingHelper.streamCall(any(), any(), anyString(), anyString())).thenReturn(result);
        Map<String, Object> stateMap = new HashMap<>();
        stateMap.put(CONVERSATION_ID, "test-conv");
        stateMap.put(SYSTEM_PROMPT, "you are a helper");
        stateMap.put(USER_MESSAGE, "分析源码");
        stateMap.put(MESSAGES, List.of());
        stateMap.put(CURRENT_ITERATION, 3);
        stateMap.put(MAX_ITERATIONS, 10);
        stateMap.put(LLM_CALL_COUNT, 5);
        stateMap.put(FORCED_TOOL_CALL, "");
        stateMap.put(SOURCE_EVIDENCE_LEDGER, SourceEvidenceLedger.empty()
                .withSourcePath("src/main/java/vip/mate/skill/controller/SkillController.java"));

        Map<String, Object> output = createNode().apply(new OverAllState(stateMap));

        assertControlFlagsCleared(output, "evidenceInsufficientFinalAnswer");
        assertEquals("evidence_insufficient", output.get(FINISH_REASON));
        assertEquals("SkillController.java 是入口，SkillServiceImpl.java 负责业务。",
                output.get(STREAMED_CONTENT));
        assertTrue(((String) output.get(FINAL_ANSWER)).contains("证据不足"));
        assertTrue(((String) output.get(FINAL_ANSWER)).contains("SkillServiceImpl.java"));
        assertEquals(false, output.get(CONTENT_STREAMED),
                "warning suffix should be broadcast and persisted as a visible final delta");
    }

    // ===== 工具调用 =====

    @Test
    @DisplayName("tool call 路径：output 包含 needsToolCall=true + shouldSummarize=false")
    void toolCall_clearsShouldSummarize() throws Exception {
        AssistantMessage.ToolCall tc = new AssistantMessage.ToolCall("id1", "function", "search", "{}");
        AssistantMessage msg = AssistantMessage.builder().content("").toolCalls(List.of(tc)).build();
        NodeStreamingChatHelper.StreamResult result = new NodeStreamingChatHelper.StreamResult(
                "", "", msg, List.of(tc), true, 100, 50);
        when(streamingHelper.streamCall(any(), any(), anyString(), anyString())).thenReturn(result);

        Map<String, Object> output = createNode().apply(buildStaleState());

        assertEquals(true, output.get(NEEDS_TOOL_CALL));
        assertEquals(false, output.get(SHOULD_SUMMARIZE),
                "toolCall: shouldSummarize should be explicitly false");
        assertLlmCallCountWritten(output, "toolCall");
    }

    // ===== Fatal error =====

    @Test
    @DisplayName("fatal error 路径：output 包含 needsToolCall=false + shouldSummarize=false + finalAnswer=错误文案")
    void fatalError_clearsControlFlags() throws Exception {
        NodeStreamingChatHelper.StreamResult result = new NodeStreamingChatHelper.StreamResult(
                "", "", new AssistantMessage(""),
                List.of(), false, 0, 0, false, "认证失败: Invalid API Key",
                NodeStreamingChatHelper.ErrorType.AUTH_ERROR);
        when(streamingHelper.streamCall(any(), any(), anyString(), anyString())).thenReturn(result);

        Map<String, Object> output = createNode().apply(buildStaleState());

        assertControlFlagsCleared(output, "fatalError");
        assertLlmCallCountWritten(output, "fatalError");
        String answer = (String) output.get(FINAL_ANSWER);
        assertNotNull(answer);
        assertTrue(answer.contains("认证失败"), "Fatal error answer should contain error message");
        assertEquals("error_fallback", output.get(FINISH_REASON));
    }

    @Test
    @DisplayName("thinking-only no-content 路径：标 INCOMPLETE 并附带可重试提示")
    void thinkingOnlyCap_preservedAsIncomplete() throws Exception {
        // Simulates the "深度思考 ... 5.4k chars never finishes" symptom:
        // helper disposes the stream after THINKING_ONLY_HARD_CAP_CHARS of
        // reasoning_content with zero visible content/tools. ReasoningNode
        // surfaces a short fallback line and preserves the thinking transcript.
        String thinkingTranscript = "我先读 X，再读 Y，再读 Z…".repeat(64);
        NodeStreamingChatHelper.StreamResult result = new NodeStreamingChatHelper.StreamResult(
                "", thinkingTranscript, new AssistantMessage(""),
                List.of(), false, 0, 600, true, "thinking_only_no_content",
                NodeStreamingChatHelper.ErrorType.UNKNOWN);
        when(streamingHelper.streamCall(any(), any(), anyString(), anyString())).thenReturn(result);

        Map<String, Object> output = createNode().apply(buildStaleState());

        assertControlFlagsCleared(output, "thinkingOnlyCap");
        assertLlmCallCountWritten(output, "thinkingOnlyCap");
        assertEquals("incomplete", output.get(FINISH_REASON));
        String answer = (String) output.get(FINAL_ANSWER);
        assertNotNull(answer);
        assertTrue(answer.contains("思考阶段"),
                "Fallback line should explain the thinking-only loop to the user");
        assertEquals(thinkingTranscript, output.get(FINAL_THINKING),
                "Thinking transcript must be preserved for the UI's collapse panel");
    }

    // ===== CancellationException (no content stop) =====

    @Test
    @DisplayName("CancellationException 路径：output 包含 needsToolCall=false + shouldSummarize=false + STOPPED")
    void cancellation_clearsControlFlags() throws Exception {
        when(streamingHelper.streamCall(any(), any(), anyString(), anyString()))
                .thenThrow(new CancellationException("Stream stopped by user"));

        Map<String, Object> output = createNode().apply(buildStaleState());

        assertControlFlagsCleared(output, "cancellation");
        assertLlmCallCountWritten(output, "cancellation");
        assertEquals("stopped", output.get(FINISH_REASON));
        assertEquals("", output.get(FINAL_ANSWER));
    }

    // ===== Stopped with partial content =====

    @Test
    @DisplayName("stopped-with-partial 路径：output 包含 needsToolCall=false + shouldSummarize=false + STOPPED")
    void stoppedWithPartial_clearsControlFlags() throws Exception {
        NodeStreamingChatHelper.StreamResult result = new NodeStreamingChatHelper.StreamResult(
                "部分内容", "部分思考", new AssistantMessage("部分内容"),
                List.of(), false, 100, 30, true, null,
                NodeStreamingChatHelper.ErrorType.NONE, true);
        when(streamingHelper.streamCall(any(), any(), anyString(), anyString())).thenReturn(result);

        Map<String, Object> output = createNode().apply(buildStaleState());

        assertControlFlagsCleared(output, "stoppedWithPartial");
        assertLlmCallCountWritten(output, "stoppedWithPartial");
        assertEquals("stopped", output.get(FINISH_REASON));
        assertEquals("部分内容", output.get(FINAL_ANSWER));
    }
}
