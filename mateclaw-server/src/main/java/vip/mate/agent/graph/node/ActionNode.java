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
 * @author MateClaw Team
 */
@Slf4j
public class ActionNode implements NodeAction {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** Function name of the explicit skill-load tool, mirrored from SkillLoadTool. */
    private static final String LOAD_SKILL_TOOL = "load_skill";

    /** Function name of the extension-tool activator, mirrored from EnableExtensionTool. */
    private static final String ENABLE_TOOL = "enable_tool";

    private final ToolExecutionExecutor executor;
    private final vip.mate.channel.web.ChatStreamTracker streamTracker;

    public ActionNode(ToolExecutionExecutor executor) {
        this(executor, null);
    }

    public ActionNode(ToolExecutionExecutor executor, vip.mate.channel.web.ChatStreamTracker streamTracker) {
        this.executor = executor;
        this.streamTracker = streamTracker;
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

        // Use the executor's raw-stage ledger instead of re-parsing the
        // spill-compacted responses. ToolExecutionExecutor builds this
        // ledger from the full pre-truncate text, so a 30 KB grep result
        // whose head/tail-cut version no longer mentions a path will still
        // contribute that path to the evidence pool. Falls back to empty
        // for legacy executor stubs (tests, mocks) that didn't populate
        // the new field — fine, the merge with `accessor.sourceEvidenceLedger`
        // is no-op in that case.
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
        // ObservationDispatcher will route to FinalAnswerNode (skipping the next
        // LLM call). Direct outputs and the trigger flag both live in state so
        // FinalAnswerNode can assemble the final answer verbatim.
        //
        // Priority guard: when an approval barrier ALSO fires in the same batch
        // (a direct tool ran successfully BEFORE a sibling tool that needed
        // approval), let the approval flow win. Otherwise the user would see a
        // "RETURN_DIRECT" final answer while an approval modal is still open
        // for the unresolved sibling — a confusing dual-track state. After the
        // user resolves the approval, the replay path will re-execute and the
        // direct tool's content reaches the user via the streamedContent path
        // instead. Same-batch direct+approval is rare; we explicitly defer to
        // approval for safety.
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
        // catalog ranks them first and the model stops re-loading the same
        // skill it already pulled into message history. Tools cannot mutate
        // graph state directly, so the load is detected here from the tool
        // calls and merged into LOADED_SKILLS (read-merge-write, REPLACE key).
        Set<String> requestedSkills = extractLoadedSkillNames(toolCalls);
        if (!requestedSkills.isEmpty()) {
            Set<String> merged = new LinkedHashSet<>(accessor.loadedSkills());
            if (merged.addAll(requestedSkills)) {
                output.loadedSkills(Set.copyOf(merged));
            }
        }

        // Same mechanism for enable_tool: record the activated extension tools so
        // ReasoningNode's next turn adds them back to the advertised callbacks.
        Set<String> enabledTools = extractEnabledToolNames(toolCalls);
        if (!enabledTools.isEmpty()) {
            Set<String> merged = new LinkedHashSet<>(accessor.enabledExtensionTools());
            if (merged.addAll(enabledTools)) {
                output.enabledExtensionTools(Set.copyOf(merged));
            }
        }

        return output.build();
    }

    /**
     * Extract the {@code toolName} argument of every {@code enable_tool} call in
     * this batch. Like {@link #extractLoadedSkillNames}, an unknown name is
     * harmless: the reasoning-node split only activates names that resolve to an
     * extension-tier tool actually in the agent's set.
     */
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

    /**
     * Extract the {@code skillName} argument of every {@code load_skill} call in
     * this batch. The names are used only to bias catalog ordering, so an
     * unparseable or unknown name is harmless (it simply never matches a
     * visible skill) — failures are swallowed rather than aborting the batch.
     */
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

    /**
     * Read the first present, non-null string value among {@code keys} from a
     * tool-call arguments JSON object. Returns null on malformed JSON or when
     * none of the keys are present.
     */
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
