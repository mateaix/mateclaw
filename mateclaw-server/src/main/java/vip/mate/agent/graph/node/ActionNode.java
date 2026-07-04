package vip.mate.agent.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import vip.mate.agent.graph.executor.ToolExecutionExecutor;
import vip.mate.agent.graph.state.MateClawStateAccessor;
import vip.mate.agent.graph.state.MateClawStateKeys;
import vip.mate.agent.graph.state.SourceEvidenceLedger;

import java.util.*;
import java.util.concurrent.CancellationException;

import static vip.mate.agent.graph.state.MateClawStateKeys.*;

/**
 * 工具执行节点（ReAct Action 阶段）
 * <p>
 * 委托 {@link ToolExecutionExecutor} 执行工具调用，支持并发执行和审批 barrier。
 * <p>
 * 支持 forced_replay 阶段：当审批通过后的重放调用到达时，跳过 ToolGuard 检查直接执行。
 *
 * <p>B2: 当检测到 {@code load_skill} 调用时，从 skill manifest 提取
 * {@code constraints} 并写入 ProgressLedger 的 pinned entries，使约束
 * 在整个对话中可见、不被 LLM 的 progress_update 覆盖、不被上下文压缩裁剪。
 *
 * <p>B5: 工具调用成功后自动回填 ProgressLedger，让 LLM 即使不主动调用
 * progress_update 也能在下一轮看到已完成的工具调用记录。自动记录条数有上限
 * （{@link ProgressLedgerService#MAX_AUTO_RECORDED}），且不覆盖 LLM 已写的条目。
 *
 * @author MateClaw Team
 */
@Slf4j
public class ActionNode implements NodeAction {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** Function name of the explicit skill-load tool, mirrored from SkillLoadTool. */
    private static final String LOAD_SKILL_TOOL = "load_skill";

    /** Function name of the extension-tool activator, mirrored from EnableExtensionTool. */
    private static final String ENABLE_TOOL = "enable_tool";

    /** Function name of the progress-update tool — skip auto-recording it. */
    private static final String PROGRESS_UPDATE_TOOL = "progress_update";

    /**
     * Tools whose results should NOT be auto-recorded into the ledger.
     * Meta-tools (load_skill, enable_tool, progress_update) either have
     * their own ledger side-effects or are the ledger itself.
     */
    private static final Set<String> AUTO_RECORD_SKIP = Set.of(
            LOAD_SKILL_TOOL, ENABLE_TOOL, PROGRESS_UPDATE_TOOL,
            "listAvailableSkills", "readSkillFile", "runSkillScript"
    );

    private final ToolExecutionExecutor executor;
    private final vip.mate.channel.web.ChatStreamTracker streamTracker;

    /** Optional — B2: extract constraints from skill manifest on load_skill. */
    private vip.mate.skill.runtime.SkillRuntimeService skillRuntimeService;
    /** Optional — B2/B5: write pinned + auto-recorded entries. */
    private vip.mate.agent.progress.ProgressLedgerService progressLedgerService;

    public ActionNode(ToolExecutionExecutor executor) {
        this(executor, null);
    }

    public ActionNode(ToolExecutionExecutor executor, vip.mate.channel.web.ChatStreamTracker streamTracker) {
        this.executor = executor;
        this.streamTracker = streamTracker;
    }

    /** Setter injection so existing constructors stay source-compatible. */
    public void setSkillRuntimeService(vip.mate.skill.runtime.SkillRuntimeService s) {
        this.skillRuntimeService = s;
    }

    /** Setter injection so existing constructors stay source-compatible. */
    public void setProgressLedgerService(vip.mate.agent.progress.ProgressLedgerService s) {
        this.progressLedgerService = s;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> apply(OverAllState state) throws Exception {
        List<AssistantMessage.ToolCall> toolCalls = state.<List<AssistantMessage.ToolCall>>value(TOOL_CALLS)
                .orElse(List.of());

        MateClawStateAccessor accessor = new MateClawStateAccessor(state);
        String conversationId = accessor.conversationId();
        String agentId = accessor.agentId();

        // 检查停止标志
        if (streamTracker != null && streamTracker.isStopRequested(conversationId)) {
            log.info("[ActionNode] Stop requested, aborting tool execution: conversationId={}", conversationId);
            throw new CancellationException("Stream stopped by user");
        }

        // 检测是否为 forced_replay 阶段（审批通过后的重放）
        String currentPhase = state.value(MateClawStateKeys.CURRENT_PHASE, "");
        boolean isReplay = "forced_replay".equals(currentPhase);

        // 请求者身份（用于审批记录）
        String requesterId = accessor.requesterId();

        // 获取工作区活动目录
        String workspaceBasePath = state.value(MateClawStateKeys.WORKSPACE_BASE_PATH, "");

        // RFC-063r §2.5: read the originating ChatOrigin from graph state and
        // forward it into the executor — tools see it via Spring AI ToolContext.
        vip.mate.agent.context.ChatOrigin origin = accessor.chatOrigin();

        // 委托 ToolExecutionExecutor 执行（两阶段：顺序 Guard + 分段并发执行）
        ToolExecutionExecutor.ToolExecutionResult result = executor.execute(
                toolCalls, conversationId, agentId, isReplay, requesterId, workspaceBasePath, origin);

        ToolResponseMessage toolResponseMessage = ToolResponseMessage.builder()
                .responses(result.responses())
                .build();

        SourceEvidenceLedger rawLedger = result.rawEvidenceLedger() != null
                ? result.rawEvidenceLedger()
                : SourceEvidenceLedger.empty();
        MateClawStateAccessor.OutputBuilder output = MateClawStateAccessor.output()
                .toolResults(result.responses())
                .messages(List.of((Message) toolResponseMessage))
                .currentPhase("action")
                .events(result.events())
                .sourceEvidenceLedger(accessor.sourceEvidenceLedger().merge(rawLedger));

        if (result.awaitingApproval()) {
            output.awaitingApproval(true);
            log.info("[ActionNode] Approval pending detected, setting AWAITING_APPROVAL=true to terminate graph");
        }

        // RFC-052: any returnDirect tool in this batch ⇒ short-circuit the graph.
        if (result.hasDirectOutputs() && !result.awaitingApproval()) {
            output.returnDirectTriggered(true);
            output.directToolOutputs(result.directOutputs());
            log.info("[ActionNode] RETURN_DIRECT_TRIGGERED — {} direct tool output(s), " +
                    "graph will route to FinalAnswerNode without re-entering LLM",
                    result.directOutputs().size());
        } else if (result.hasDirectOutputs() && result.awaitingApproval()) {
            log.warn("[ActionNode] Mixed batch: {} direct output(s) co-occurring with approval " +
                    "barrier on '{}'; deferring to approval flow (RFC-052 §6.5)",
                    result.directOutputs().size(),
                    result.barrierToolName() != null ? result.barrierToolName() : "unknown");
        }

        // replay 完成后清空 forced_tool_call，防止下一轮再触发
        if (isReplay) {
            output.forcedToolCall("");
        }

        // Pin skills the model loaded this run so the next reasoning turn's
        // catalog ranks them first.
        Set<String> requestedSkills = extractLoadedSkillNames(toolCalls);
        if (!requestedSkills.isEmpty()) {
            Set<String> merged = new LinkedHashSet<>(accessor.loadedSkills());
            if (merged.addAll(requestedSkills)) {
                output.loadedSkills(Set.copyOf(merged));
            }
            // B2: extract structured constraints from loaded skills' manifests
            // and pin them into the ProgressLedger so they survive context
            // compression and stay visible on every turn.
            pinSkillConstraints(conversationId, requestedSkills);
        }

        // Same mechanism for enable_tool
        Set<String> enabledTools = extractEnabledToolNames(toolCalls);
        if (!enabledTools.isEmpty()) {
            Set<String> merged = new LinkedHashSet<>(accessor.enabledExtensionTools());
            if (merged.addAll(enabledTools)) {
                output.enabledExtensionTools(Set.copyOf(merged));
            }
        }

        // B5: auto-record successful tool calls into the ledger so the LLM
        // sees what it already did even if it forgot to call progress_update.
        // Skips meta-tools (load_skill, enable_tool, progress_update) and
        // doesn't overwrite LLM-authored entries.
        autoRecordToolCalls(conversationId, result.responses());

        return output.build();
    }

    // ==================== B2: Pin skill constraints ====================

    /**
     * For each loaded skill, extract the manifest's {@code constraints} list
     * and write them as pinned entries in the ProgressLedger. Pinned entries
     * live in {@code nonHistoryPrefix} (never trimmed) and are never
     * overwritten by the LLM's {@code progress_update} tool.
     *
     * <p>Failures are swallowed — a missing manifest or a ledger write error
     * must never abort the tool execution batch.
     */
    private void pinSkillConstraints(String conversationId, Set<String> skillNames) {
        if (progressLedgerService == null || skillRuntimeService == null
                || conversationId == null || conversationId.isBlank()) {
            return;
        }
        for (String skillName : skillNames) {
            try {
                vip.mate.skill.runtime.model.ResolvedSkill skill = skillRuntimeService.findActiveSkill(skillName);
                if (skill == null || skill.getManifest() == null) {
                    continue;
                }
                List<String> constraints = skill.getManifest().getConstraints();
                if (constraints == null || constraints.isEmpty()) {
                    continue;
                }
                // Clear old pinned entries for this skill first (handles re-load after update).
                String keyPrefix = "pin_" + skillName + "_";
                progressLedgerService.clearPinnedByPrefix(conversationId, keyPrefix);
                // Write each constraint as a pinned entry.
                for (int i = 0; i < constraints.size(); i++) {
                    String constraint = constraints.get(i);
                    if (constraint == null || constraint.isBlank()) {
                        continue;
                    }
                    String key = keyPrefix + i;
                    progressLedgerService.upsertPinned(conversationId, key,
                            "🔒 " + skillName + ": " + truncate(constraint, 100), constraint);
                }
                log.info("[ActionNode] Pinned {} constraint(s) from skill '{}' for conv {}",
                        constraints.size(), skillName, conversationId);
            } catch (Exception e) {
                log.warn("[ActionNode] Failed to pin constraints for skill '{}': {}",
                        skillName, e.getMessage());
            }
        }
    }

    // ==================== B5: Auto-record tool calls ====================

    /**
     * Auto-record each successful tool call as a ledger entry with key
     * {@code auto_<toolName>}. Bounded to {@link ProgressLedgerService#MAX_AUTO_RECORDED}
     * most recent entries. Skips meta-tools and doesn't overwrite LLM entries.
     *
     * <p>Key uniqueness: for MCP tools the FULL prefixed name
     * ({@code mcp_<serverId>_<slug>_<hash6>}) is used as the key suffix to
     * avoid collisions between servers that expose tools with the same slug.
     * The display label uses the simplified slug for readability.
     */
    private void autoRecordToolCalls(String conversationId,
                                     List<ToolResponseMessage.ToolResponse> responses) {
        if (progressLedgerService == null || conversationId == null
                || conversationId.isBlank() || responses == null || responses.isEmpty()) {
            return;
        }
        // Collect valid entries first, then persist in a single batch to avoid
        // N separate lock+load+save cycles when the LLM calls tools in parallel.
        List<vip.mate.agent.progress.ProgressLedgerService.AutoRecordEntry> batch = new java.util.ArrayList<>();
        for (ToolResponseMessage.ToolResponse resp : responses) {
            String toolName = resp.name();
            if (toolName == null || toolName.isBlank() || AUTO_RECORD_SKIP.contains(toolName)) {
                continue;
            }
            // Use the full tool name as the key (unique across MCP servers),
            // but the simplified slug as the display label (readable).
            String displayName = simplifyToolName(toolName);
            String summary = resp.responseData();
            if (summary != null && summary.length() > 120) {
                summary = summary.substring(0, 120) + "…";
            }
            batch.add(new vip.mate.agent.progress.ProgressLedgerService.AutoRecordEntry(
                    toolName, displayName, summary));
        }
        if (batch.isEmpty()) {
            return;
        }
        try {
            progressLedgerService.upsertAutoRecordedBatch(conversationId, batch);
        } catch (Exception e) {
            log.debug("[ActionNode] Batch auto-record failed for {} tools: {}",
                    batch.size(), e.getMessage());
        }
    }

    /**
     * Simplify an MCP tool name ({@code mcp_<serverId>_<slug>_<hash6>})
     * to just the slug for ledger readability. Non-MCP names pass through.
     */
    private static String simplifyToolName(String name) {
        if (name == null) return "unknown";
        if (!name.startsWith("mcp_")) return name;
        // mcp_<serverId>_<slug>_<hash6> → <slug>
        int firstSep = name.indexOf('_', 4);
        int lastSep = name.lastIndexOf('_');
        if (firstSep > 0 && lastSep > firstSep) {
            String slug = name.substring(firstSep + 1, lastSep);
            return slug.isEmpty() ? name : slug;
        }
        return name;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    // ==================== Existing helpers ====================

    static Set<String> extractEnabledToolNames(List<AssistantMessage.ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return Set.of();
        }
        Set<String> names = new LinkedHashSet<>();
        for (AssistantMessage.ToolCall tc : toolCalls) {
            if (tc == null || !ENABLE_TOOL.equals(tc.name())) {
                continue;
            }
            String name = parseStringArg(tc.arguments(), "toolName", "tool_name", "name");
            if (name != null && !name.isBlank()) {
                names.add(name.trim());
            }
        }
        return names;
    }

    static Set<String> extractLoadedSkillNames(List<AssistantMessage.ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return Set.of();
        }
        Set<String> names = new LinkedHashSet<>();
        for (AssistantMessage.ToolCall tc : toolCalls) {
            if (tc == null || !LOAD_SKILL_TOOL.equals(tc.name())) {
                continue;
            }
            String name = parseStringArg(tc.arguments(), "skillName", "skill_name", "name");
            if (name != null && !name.isBlank()) {
                names.add(name.trim());
            }
        }
        return names;
    }

    private static String parseStringArg(String argumentsJson, String... keys) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return null;
        }
        try {
            JsonNode node = OBJECT_MAPPER.readTree(argumentsJson);
            for (String key : keys) {
                JsonNode value = node.get(key);
                if (value != null && !value.isNull()) {
                    return value.asText();
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}
