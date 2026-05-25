package vip.mate.memory.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.workspace.conversation.model.MessageEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MemorySummarizationGateTest {

    @Test
    @DisplayName("skips conversations whose final assistant message is evidence_insufficient")
    void skipsEvidenceInsufficientTurns() {
        MessageEntity user = message("user", "分析 MateClaw 技能系统源码", null);
        MessageEntity assistant = message("assistant", "SkillServiceImpl.java 负责业务。",
                "{\"finishReason\":\"evidence_insufficient\"}");

        MemorySummarizationGate.Decision decision =
                MemorySummarizationGate.evaluate(List.of(user, assistant));

        assertFalse(decision.shouldAnalyze());
        assertTrue(decision.reason().contains("finishReason"));
    }

    @Test
    @DisplayName("skips evidence warning answers even when metadata does not carry finishReason")
    void skipsEvidenceWarningContent() {
        MessageEntity user = message("user", "分析系统设计", null);
        MessageEntity assistant = message("assistant",
                "结论如下。\n\n[证据不足] 以下源码引用未出现在已读取/搜索到的工具证据中：SkillServiceImpl.java。",
                "{}");

        MemorySummarizationGate.Decision decision =
                MemorySummarizationGate.evaluate(List.of(user, assistant));

        assertFalse(decision.shouldAnalyze());
        assertTrue(decision.reason().contains("assistant content"));
    }

    @Test
    @DisplayName("skips one-off source analysis even when the assistant message is completed")
    void skipsSourceAnalysisTasks() {
        MessageEntity user = message("user", "请全面 review skill 技能功能源码，看看有哪些待修复内容", null);
        MessageEntity assistant = message("assistant", "已分析 SkillController.java。",
                "{\"finishReason\":\"normal\"}");

        MemorySummarizationGate.Decision decision =
                MemorySummarizationGate.evaluate(List.of(user, assistant));

        assertFalse(decision.shouldAnalyze());
        assertTrue(decision.reason().contains("source-analysis"));
    }

    @Test
    @DisplayName("allows explicit remember requests")
    void allowsExplicitRememberRequests() {
        MessageEntity user = message("user", "记住：这个项目后端默认用 MyBatis Plus 分页", null);
        MessageEntity assistant = message("assistant", "已记录。", "{\"finishReason\":\"normal\"}");

        MemorySummarizationGate.Decision decision =
                MemorySummarizationGate.evaluate(List.of(user, assistant));

        assertTrue(decision.shouldAnalyze());
    }

    @Test
    @DisplayName("skips incomplete turns once finishReason rides in metadata (regression for the lifecycle sink)")
    void skipsIncompleteFinishReason() {
        // Critical regression: the new INCOMPLETE fallback texts produced by the
        // repetition / thinking-only soft caps do NOT match the text heuristic
        // ("自动截断" is not in the heuristic list). Without finishReason in
        // metadata they would silently leak into long-term memory. After the
        // ReActLifecycleListener finishReasonSink wiring, INCOMPLETE rides in
        // metadata and the gate skips on it.
        MessageEntity user = message("user", "分析这段代码", null);
        MessageEntity assistant = message("assistant",
                "（模型输出被自动截断且未产出可见内容，请重试。）",
                "{\"finishReason\":\"incomplete\"}");

        MemorySummarizationGate.Decision decision =
                MemorySummarizationGate.evaluate(List.of(user, assistant));

        assertFalse(decision.shouldAnalyze());
        assertTrue(decision.reason().contains("incomplete"),
                "reason must surface the actual finishReason for log/debug");
    }

    @Test
    @DisplayName("skips stopped turns based on finishReason metadata")
    void skipsStoppedFinishReason() {
        MessageEntity user = message("user", "做一个表格", null);
        MessageEntity assistant = message("assistant", "已停止生成的部分内容…",
                "{\"finishReason\":\"stopped\"}");

        MemorySummarizationGate.Decision decision =
                MemorySummarizationGate.evaluate(List.of(user, assistant));

        assertFalse(decision.shouldAnalyze());
    }

    @Test
    @DisplayName("skips error_fallback turns based on finishReason metadata")
    void skipsErrorFallbackFinishReason() {
        // Even when the visible content does not include "error_fallback" verbatim,
        // metadata-based detection short-circuits the text heuristic.
        MessageEntity user = message("user", "做点事", null);
        MessageEntity assistant = message("assistant", "[错误] 认证失败: Invalid API Key",
                "{\"finishReason\":\"error_fallback\"}");

        MemorySummarizationGate.Decision decision =
                MemorySummarizationGate.evaluate(List.of(user, assistant));

        assertFalse(decision.shouldAnalyze());
    }

    @Test
    @DisplayName("return_direct turns are eligible (tool-direct outputs are durable)")
    void allowsReturnDirectFinishReason() {
        MessageEntity user = message("user", "随便聊聊", null);
        MessageEntity assistant = message("assistant", "工具直接返回的内容。",
                "{\"finishReason\":\"return_direct\"}");

        MemorySummarizationGate.Decision decision =
                MemorySummarizationGate.evaluate(List.of(user, assistant));

        assertTrue(decision.shouldAnalyze(),
                "return_direct represents a successful tool-driven answer; should reach analysis");
    }

    private static MessageEntity message(String role, String content, String metadata) {
        MessageEntity entity = new MessageEntity();
        entity.setRole(role);
        entity.setContent(content);
        entity.setMetadata(metadata);
        return entity;
    }
}
