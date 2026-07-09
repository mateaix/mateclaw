package vip.mate.wiki.service;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;
import vip.mate.agent.AgentGraphBuilder;
import vip.mate.agent.prompt.PromptLoader;
import vip.mate.channel.web.ChatStreamTracker;
import vip.mate.i18n.I18nService;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.service.ModelConfigService;
import vip.mate.wiki.model.WikiRawMaterialEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * RFC-011 Phase 3: Wiki Deep Research 服务
 * <p>
 * 三阶段管线（不走 StateGraph 框架，保持轻量）：
 * <ol>
 *   <li><b>Plan</b>：LLM 把 topic 拆为 3-5 个子问题</li>
 *   <li><b>Retrieve + Draft</b>：并行对每个子问题调 {@link HybridRetriever} + LLM 起草段落</li>
 *   <li><b>Compose</b>：LLM 把段落组装为最终报告</li>
 * </ol>
 * 事件通过 {@link ChatStreamTracker#broadcast} 推送，前端用 SSE 订阅。
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WikiResearchService {

    private final HybridRetriever hybridRetriever;
    private final WikiRawMaterialService rawService;
    private final ModelConfigService modelConfigService;
    private final AgentGraphBuilder agentGraphBuilder;
    private final ChatStreamTracker streamTracker;
    private final I18nService i18n;

    private static final RetryTemplate NO_RETRY = RetryTemplate.builder().maxAttempts(1).build();
    private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();
    private static final int DEFAULT_TOP_K_PER_QUESTION = 5;
    private static final int MAX_PARALLEL_QUESTIONS = 3;

    /**
     * 执行 Deep Research，通过 SSE 流式推送进度
     *
     * @param kbId           知识库 ID
     * @param topic          研究主题
     * @param sessionId      SSE 会话 ID（前端订阅用）
     * @param topKPerQuestion 每个子问题召回的材料数（默认 5）
     * @return 最终报告
     */
    public ResearchResult research(Long kbId, String topic, String sessionId, Integer topKPerQuestion) {
        int topK = topKPerQuestion != null && topKPerQuestion > 0 ? topKPerQuestion : DEFAULT_TOP_K_PER_QUESTION;
        log.info("[Research] Start: kbId={}, topic={}, sessionId={}", kbId, topic, sessionId);

        try {
            // Stage 1: Plan
            List<SubQuestion> questions = planStage(topic);
            if (questions.isEmpty()) {
                broadcast(sessionId, "research.error", Map.of("message", i18n.msg("research.broadcast.no_plan")));
                return new ResearchResult(topic, List.of(), i18n.msg("research.fallback.no_plan"));
            }
            broadcast(sessionId, "research.plan", Map.of(
                    "questions", questions.stream().map(q -> Map.of("question", q.question, "intent", q.intent)).toList()
            ));
            // Cooperative cancellation: the Open API cancel endpoint calls
            // streamTracker.requestStop(sessionId). Bail before the expensive
            // retrieve+draft fan-out so cancel actually halts LLM cost.
            ensureNotCancelled(sessionId);

            // Stage 2: Retrieve + Draft (并行)
            List<Section> sections = draftStage(kbId, questions, topK, sessionId);
            if (sections.stream().allMatch(s -> s.content == null || s.content.isBlank())) {
                broadcast(sessionId, "research.error", Map.of("message", i18n.msg("research.broadcast.draft_all_empty")));
                return new ResearchResult(topic, sections, i18n.msg("research.fallback.no_materials"));
            }
            // Second checkpoint before the compose LLM call.
            ensureNotCancelled(sessionId);

            // Stage 3: Compose
            String report = composeStage(topic, sections);
            broadcast(sessionId, "research.done", Map.of(
                    "report", report,
                    "sections", sections.size(),
                    "materialsUsed", sections.stream().flatMap(s -> s.materialRefs.stream()).distinct().count()
            ));
            return new ResearchResult(topic, sections, report);
        } catch (ResearchCancelledException ce) {
            // Expected: caller has already flipped the session to CANCELLED
            // and closed the SSE stream. Do not broadcast an error event.
            log.info("[Research] Cancelled: kbId={}, topic={}, sessionId={}", kbId, topic, sessionId);
            return new ResearchResult(topic, List.of(), "Research cancelled by user");
        } catch (Exception e) {
            log.error("[Research] Failed: kbId={}, topic={}: {}", kbId, topic, e.getMessage(), e);
            broadcast(sessionId, "research.error", Map.of("message", e.getMessage() != null ? e.getMessage() : i18n.msg("research.broadcast.failed")));
            return new ResearchResult(topic, List.of(), i18n.msg("research.fallback.failed", e.getMessage()));
        }
    }

    /**
     * Throws {@link ResearchCancelledException} if the caller (via the Open API
     * cancel endpoint) has called {@link ChatStreamTracker#requestStop} on this
     * session. Checked at each pipeline stage boundary.
     */
    private void ensureNotCancelled(String sessionId) {
        if (streamTracker.isStopRequested(sessionId)) {
            throw new ResearchCancelledException(sessionId);
        }
    }

    // ==================== Stage 1: Plan ====================

    private List<SubQuestion> planStage(String topic) {
        String systemPrompt = PromptLoader.loadPrompt("research/plan-system");
        String userPrompt = PromptLoader.loadPrompt("research/plan-user").replace("{topic}", topic);

        String response = callLlm(systemPrompt, userPrompt, "plan");
        if (response == null || response.isBlank()) return List.of();

        try {
            String cleaned = stripCodeFences(response);
            JSONObject obj = JSONUtil.parseObj(cleaned);
            JSONArray arr = obj.getJSONArray("questions");
            if (arr == null) return List.of();
            List<SubQuestion> questions = new ArrayList<>();
            for (Object item : arr) {
                JSONObject q = (JSONObject) item;
                String question = q.getStr("question");
                String intent = q.getStr("intent", "");
                if (question != null && !question.isBlank()) {
                    questions.add(new SubQuestion(question, intent));
                }
            }
            log.info("[Research] Plan produced {} sub-questions", questions.size());
            return questions;
        } catch (Exception e) {
            log.warn("[Research] Plan JSON parse failed: {}", e.getMessage());
            return List.of();
        }
    }

    // ==================== Stage 2: Retrieve + Draft ====================

    private List<Section> draftStage(Long kbId, List<SubQuestion> questions, int topK, String sessionId) {
        Semaphore semaphore = new Semaphore(MAX_PARALLEL_QUESTIONS);
        List<CompletableFuture<Section>> futures = new ArrayList<>(questions.size());

        for (int i = 0; i < questions.size(); i++) {
            final int idx = i;
            final SubQuestion q = questions.get(i);
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    semaphore.acquire();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return new Section(q.question, "", List.of());
                }
                // Re-check cancellation inside the parallel draft loop too —
                // draftOneSection issues its own LLM call, which is the main
                // cost driver, so skip queued-but-not-started drafts on cancel.
                if (streamTracker.isStopRequested(sessionId)) {
                    return new Section(q.question, "", List.of());
                }
                try {
                    Section section = draftOneSection(kbId, q, topK);
                    broadcast(sessionId, "research.draft", Map.of(
                            "index", idx,
                            "question", q.question,
                            "content", section.content,
                            "materialRefs", section.materialRefs
                    ));
                    return section;
                } finally {
                    semaphore.release();
                }
            }, EXECUTOR));
        }

        return futures.stream()
                .map(CompletableFuture::join)
                .toList();
    }

    private Section draftOneSection(Long kbId, SubQuestion q, int topK) {
        // 检索 chunk 级材料片段
        List<HybridRetriever.ChunkHit> hits = hybridRetriever.searchChunks(kbId, q.question, topK);

        if (hits.isEmpty()) {
            return new Section(q.question, i18n.msg("research.fallback.no_materials_for_question"), List.of());
        }

        // Assemble material snippets with neutral [M1]/[M2]... markers so the
        // LLM is not biased toward any output language. The same `[Mn]` token
        // is the citation format the draft prompt asks for, so the model can
        // refer to materials without translation.
        StringBuilder materials = new StringBuilder();
        List<MaterialRef> refs = new ArrayList<>();
        Map<Long, String> rawTitleCache = new HashMap<>();

        for (int i = 0; i < hits.size(); i++) {
            HybridRetriever.ChunkHit hit = hits.get(i);
            String rawTitle = rawTitleCache.computeIfAbsent(hit.rawId(), id -> {
                WikiRawMaterialEntity raw = rawService.getById(id);
                return raw != null ? raw.getTitle() : "unknown";
            });
            materials.append("### [M").append(i + 1).append("] Source: ").append(rawTitle).append("\n")
                    .append(hit.snippet())
                    .append("\n\n");
            refs.add(new MaterialRef(i + 1, hit.chunkId(), hit.rawId(), rawTitle));
        }

        String systemPrompt = PromptLoader.loadPrompt("research/draft-system");
        String userPrompt = PromptLoader.loadPrompt("research/draft-user")
                .replace("{question}", q.question)
                .replace("{intent}", q.intent != null ? q.intent : "")
                .replace("{materials}", materials.toString());

        String content = callLlm(systemPrompt, userPrompt, "draft: " + q.question);
        if (content == null || content.isBlank()) {
            content = i18n.msg("research.fallback.draft_empty");
        }

        return new Section(q.question, content, refs);
    }

    // ==================== Stage 3: Compose ====================

    private String composeStage(String topic, List<Section> sections) {
        StringBuilder sectionsText = new StringBuilder();
        LinkedHashMap<Integer, String> usedMaterials = new LinkedHashMap<>();

        for (int i = 0; i < sections.size(); i++) {
            Section s = sections.get(i);
            // Use neutral [Q1]/[Q2] tokens — keeps prompt and (in compose-failure
            // fallback) the report itself language-independent.
            sectionsText.append("### [Q").append(i + 1).append("] ").append(s.question).append("\n");
            sectionsText.append(s.content).append("\n\n");
            for (MaterialRef ref : s.materialRefs) {
                usedMaterials.putIfAbsent(ref.index, ref.rawTitle);
            }
        }

        StringBuilder materialsRef = new StringBuilder();
        usedMaterials.forEach((idx, title) ->
                materialsRef.append("- [M").append(idx).append("] ").append(title).append("\n"));

        String systemPrompt = PromptLoader.loadPrompt("research/compose-system");
        String userPrompt = PromptLoader.loadPrompt("research/compose-user")
                .replace("{topic}", topic)
                .replace("{sections}", sectionsText.toString())
                .replace("{materials_ref}", materialsRef.toString());

        String report = callLlm(systemPrompt, userPrompt, "compose");
        if (report == null || report.isBlank()) {
            // 降级：直接拼接段落
            return "# " + topic + "\n\n" + sectionsText;
        }
        return report;
    }

    // ==================== Helpers ====================

    private String callLlm(String systemPrompt, String userPrompt, String ctx) {
        try {
            ChatModel chatModel = buildChatModel();
            Prompt prompt = new Prompt(List.of(
                    new SystemMessage(systemPrompt),
                    new UserMessage(userPrompt)));
            ChatResponse response = chatModel.call(prompt);
            if (response == null || response.getResult() == null
                    || response.getResult().getOutput() == null) {
                return null;
            }
            return response.getResult().getOutput().getText();
        } catch (Exception e) {
            log.error("[Research] LLM call failed ({}): {}", ctx, e.getMessage());
            return null;
        }
    }

    private ChatModel buildChatModel() {
        ModelConfigEntity model = modelConfigService.getDefaultModel();
        return agentGraphBuilder.buildRuntimeChatModel(model, NO_RETRY);
    }

    private void broadcast(String sessionId, String eventName, Map<String, Object> payload) {
        if (sessionId == null || sessionId.isBlank()) return;
        try {
            streamTracker.broadcast(sessionId, eventName, JSONUtil.toJsonStr(payload));
        } catch (Exception e) {
            log.debug("[Research] SSE broadcast failed for {}: {}", sessionId, e.getMessage());
        }
    }

    private String stripCodeFences(String text) {
        if (text == null) return "";
        String t = text.strip();
        if (t.startsWith("```json")) t = t.substring(7);
        else if (t.startsWith("```")) t = t.substring(3);
        if (t.endsWith("```")) t = t.substring(0, t.length() - 3);
        return t.strip();
    }

    // ==================== DTO ====================

    public record SubQuestion(String question, String intent) {}

    public record MaterialRef(int index, Long chunkId, Long rawId, String rawTitle) {}

    public record Section(String question, String content, List<MaterialRef> materialRefs) {}

    public record ResearchResult(String topic, List<Section> sections, String report) {}

    /**
     * Thrown when {@link ChatStreamTracker#requestStop(String)} was called on
     * the session between pipeline stages. The {@link #research} method catches
     * this to short-circuit — the caller (the Open API controller) has already
     * flipped the registry to CANCELLED and closed the SSE stream.
     */
    public static class ResearchCancelledException extends RuntimeException {
        public ResearchCancelledException(String sessionId) {
            super("Research cancelled: sessionId=" + sessionId);
        }
    }
}
