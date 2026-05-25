package vip.mate.agent.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import vip.mate.agent.graph.NodeStreamingChatHelper;
import vip.mate.agent.graph.observation.ObservationProcessor;
import vip.mate.agent.graph.state.FinishReason;
import vip.mate.agent.graph.state.MateClawStateAccessor;
import vip.mate.agent.prompt.PromptLoader;
import vip.mate.i18n.I18nService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 超限处理节点
 * <p>
 * 当迭代次数达到 maxIterations 时由 dispatcher 路由至此节点。
 * <b>不会直接抛异常</b>，而是向 LLM 注入友好的系统提示，
 * 要求其基于已有信息给出最终回答，明确标注不确定项。
 * <p>
 * 工程化超限机制：
 * 1. 如果 observationHistory 过长，先做内联压缩
 * 2. 注入 "停止工具调用" 系统指令
 * 3. 让 LLM 生成简洁最终回答
 * 4. 标记 finishReason = MAX_ITERATIONS_REACHED
 * <p>
 * 使用 {@link NodeStreamingChatHelper} 进行流式调用，实时推送 content/thinking 增量。
 *
 * @author MateClaw Team
 */
@Slf4j
public class LimitExceededNode implements NodeAction {

    private static final String SYSTEM_TEMPLATE = PromptLoader.loadPrompt("graph/limit-exceeded-system");
    private static final String USER_TEMPLATE = PromptLoader.loadPrompt("graph/limit-exceeded-user");

    private final ChatModel chatModel;
    private final ObservationProcessor observationProcessor;
    private final NodeStreamingChatHelper streamingHelper;
    /** Optional i18n service; nullable so legacy/tests without Spring context still work. */
    private final I18nService i18n;
    /**
     * Optional ledger loader. When set, the conversation's progress snapshot
     * (done / in-progress / pending) is appended to the LLM's context so the
     * "graceful wrap-up" answer can be honest about which steps actually
     * finished and which were still pending when the iteration cap hit.
     * Null in legacy/test constructors — the wrap behaves as before.
     */
    private final vip.mate.agent.progress.ProgressLedgerService progressLedgerService;

    public LimitExceededNode(ChatModel chatModel, ObservationProcessor observationProcessor,
                             NodeStreamingChatHelper streamingHelper) {
        this(chatModel, observationProcessor, streamingHelper, null, null);
    }

    public LimitExceededNode(ChatModel chatModel, ObservationProcessor observationProcessor,
                             NodeStreamingChatHelper streamingHelper, I18nService i18n) {
        this(chatModel, observationProcessor, streamingHelper, i18n, null);
    }

    public LimitExceededNode(ChatModel chatModel, ObservationProcessor observationProcessor,
                             NodeStreamingChatHelper streamingHelper, I18nService i18n,
                             vip.mate.agent.progress.ProgressLedgerService progressLedgerService) {
        this.chatModel = chatModel;
        this.observationProcessor = observationProcessor;
        this.streamingHelper = streamingHelper;
        this.i18n = i18n;
        this.progressLedgerService = progressLedgerService;
    }

    /**
     * @deprecated use the constructor with {@link NodeStreamingChatHelper} (and optionally {@link I18nService})
     */
    @Deprecated
    public LimitExceededNode(ChatModel chatModel, ObservationProcessor observationProcessor) {
        this(chatModel, observationProcessor, null, null, null);
    }

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        MateClawStateAccessor accessor = new MateClawStateAccessor(state);

        int maxIterations = accessor.maxIterations();
        String userInput = accessor.userMessage();
        String conversationId = accessor.conversationId();
        List<String> observations = accessor.observationHistory();
        String existingSummary = accessor.summarizedContext();

        log.warn("[LimitExceededNode] Max iterations ({}) reached. Generating graceful final answer. " +
                        "Observations: {} entries, {} chars, existing summary: {} chars",
                maxIterations, observations.size(), accessor.totalObservationChars(),
                existingSummary.length());

        // 准备上下文：优先使用已有 summary，否则压缩 observationHistory
        String contextForLLM;
        if (!existingSummary.isEmpty()) {
            contextForLLM = existingSummary;
        } else if (!observations.isEmpty()) {
            // 内联压缩：拼接观察历史，截断到可控长度
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < observations.size(); i++) {
                sb.append(String.format("【第 %d 轮】%s\n", i + 1, observations.get(i)));
            }
            contextForLLM = observationProcessor.truncate(sb.toString(),
                    observationProcessor.getMaxTotalObservationChars());
        } else {
            contextForLLM = i18n != null ? i18n.msg("agent.limit_exceeded.empty_context") : "(no tool results)";
        }

        // Prepend the conversation's progress ledger snapshot when available
        // so the wrap-up answer can be honest about partial completion ("4/10
        // models researched, 6 still pending") rather than vaguely describing
        // "what I tried". Without this, hitting the iteration cap on a
        // 10-step task produces a useless catch-all message — observed in
        // round-4 of the LLM-review smoke test.
        String ledgerSnapshot = null;
        if (progressLedgerService != null && conversationId != null && !conversationId.isBlank()) {
            try {
                ledgerSnapshot = progressLedgerService.load(conversationId).renderSnapshot();
            } catch (Exception e) {
                log.warn("[LimitExceededNode] Failed to load progress ledger for {}: {}",
                        conversationId, e.getMessage());
            }
        }
        if (ledgerSnapshot != null) {
            contextForLLM = ledgerSnapshot + "\n\n---\n\n" + contextForLLM;
        }

        // 构建 prompt
        String systemPrompt = SYSTEM_TEMPLATE.replace("{maxIterations}", String.valueOf(maxIterations));
        String userPrompt = USER_TEMPLATE
                .replace("{question}", userInput)
                .replace("{context}", contextForLLM);

        List<Message> promptMessages = new ArrayList<>();
        promptMessages.add(new SystemMessage(systemPrompt));
        promptMessages.add(new UserMessage(userPrompt));

        // 流式调用 LLM，实时推送 content/thinking
        NodeStreamingChatHelper.StreamResult result = streamingHelper.streamCall(
                chatModel, new Prompt(promptMessages), conversationId, "limit_exceeded");

        String finalDraft = result.text();

        log.info("[LimitExceededNode] Generated limit-exceeded final answer: {} chars",
                finalDraft != null ? finalDraft.length() : 0);

        String fallbackMsg = i18n != null
                ? i18n.msg("agent.limit_exceeded.fallback")
                : "Sorry, the maximum reasoning steps were reached.";
        return MateClawStateAccessor.output()
                .finalAnswerDraft(finalDraft != null ? finalDraft : fallbackMsg)
                .currentThinking(result.thinking())
                .limitExceeded(true)
                .contentStreamed(true)
                .thinkingStreamed(!result.thinking().isEmpty())
                .mergeUsage(state, result)
                .finishReason(FinishReason.MAX_ITERATIONS_REACHED)
                .build();
    }
}
