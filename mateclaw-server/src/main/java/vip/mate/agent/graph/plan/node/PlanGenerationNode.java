package vip.mate.agent.graph.plan.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.util.StringUtils;
import vip.mate.agent.AgentService;
import vip.mate.agent.AgentToolSet;
import vip.mate.agent.GraphEventPublisher;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.graph.NodeStreamingChatHelper;
import vip.mate.agent.graph.plan.state.PlanStateAccessor;
import vip.mate.agent.graph.plan.state.PlanStateKeys;
import vip.mate.agent.graph.state.MateClawStateKeys;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.agent.context.ConversationWindowManager;
import vip.mate.agent.context.RuntimeContextInjector;
import vip.mate.goal.config.GoalProperties;
import vip.mate.goal.model.GoalCreateRequest;
import vip.mate.goal.model.GoalCriterion;
import vip.mate.goal.model.GoalEntity;
import vip.mate.goal.service.GoalService;
import vip.mate.planning.service.PlanningService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Task triage node for the Plan-Execute graph.
 * <p>
 * Decides one of three routes for the user's goal and emits a JSON directive:
 * <ul>
 *   <li>{@code direct_answer} — pure knowledge question, no tools, no planning</li>
 *   <li>single-step plan — needs tools but a single coherent action (steps=1)</li>
 *   <li>multi-step plan — genuinely independent subtasks (2–6 steps)</li>
 * </ul>
 * When {@code needs_planning} is false the node streams the direct answer
 * through {@link NodeStreamingChatHelper} and the graph exits via
 * {@code DirectAnswerNode}. Otherwise a plan is persisted via
 * {@link PlanningService} and {@code step_execution} takes over.
 * <p>
 * The previous version forced {@code needs_planning=true} whenever any tool
 * was required, producing multi-step plans for trivial single-hop tasks.
 * The revised prompt collapses single-hop tool use into a 1-step plan so the
 * executor can handle it with one ReAct-style iteration (see RFC-008).
 */
@Slf4j
public class PlanGenerationNode implements NodeAction {

    private final ChatModel chatModel;
    private final PlanningService planningService;
    private final NodeStreamingChatHelper streamingHelper;
    private final ConversationWindowManager conversationWindowManager;
    private final AgentToolSet toolSet;
    /** Optional — auto-derive a goal from the plan. Null disables the feature (legacy/test). */
    private final GoalService goalService;
    private final GoalProperties goalProperties;
    /** Optional — advertise delegatable specialist agents to the planner and
     *  resolve per-step assignments. Null disables per-step delegation (legacy/test). */
    private final AgentService agentService;

    /** Plan steps below this size are trivial tool tasks, not goal-worthy. */
    private static final int MIN_STEPS_FOR_AUTO_GOAL = 2;
    /** Cap the auto-derived goal title; the full request rides in the description. */
    private static final int AUTO_GOAL_TITLE_MAX = 80;

    /**
     * Structured triage result — field names use @JsonProperty to match the
     * snake_case keys the LLM is instructed to produce, so no prompt changes needed.
     */
    record TriageResult(
            @JsonProperty("needs_planning") boolean needsPlanning,
            @JsonProperty("direct_answer")  String directAnswer,
            @JsonProperty("plan_type")      String planType,
            @JsonProperty("steps")          List<String> steps,
            // Optional per-step delegation: agent names parallel to steps (same
            // order). An empty string / missing entry means "run with the parent
            // agent". Only populated when delegatable specialist agents are
            // advertised to the planner; absent for backward compatibility.
            @JsonProperty("step_agents")    List<String> stepAgents
    ) {}

    private static final String PLANNING_PROMPT = """
            你是任务分流器，不是聊天助手。根据用户目标把请求分到三类之一，并只输出一个 JSON 对象。

            硬性规则：
            1. 只返回一个 JSON 对象；不允许 markdown 代码块、不允许任何 JSON 以外的文字。
            2. 不要解释，不要寒暄，不要说"我来...""我先..."。
            3. 判断依据是"目标是否由多个明显独立的子任务/交付物组成"，而不是难度高低：
               单个连贯动作不要拆，但目标确实分成多个部分时也不要硬压成一步。

            三类分流：

            (A) 直接回答 — 简单的纯知识问答：凭自身知识用一两段话即可答完，不需要任何工具、不需要读文件、
                不需要查询当前状态，且目标本身不包含多个需要分别完成的子任务。
                （注意：成段的分析、对比、方案、规划、教程等通常不属于此类，应走 B 或 C。）
                输出：{"needs_planning": false, "direct_answer": "<你的回答>"}

            (B) 单步任务 — 本质是一个连贯动作（一次文件读取 / 一次搜索 / 一次命令 / 一次记忆读写 / 一次计算 /
                一段集中产出）。执行器会在这一步内部迭代调用多次工具，你**不要**提前拆分。
                输出：{"needs_planning": true, "steps": ["<将用户目标复述为一句清晰可执行的指令>"]}

            (C) 多步任务 — 用户目标包含 2 个及以上明显独立、需要先后完成的子任务或交付物（例如"先调研 A 再调研 B
                然后对比"、"读配置、迁移数据、验证结果"、"分阶段制定计划"、"产出由若干独立部分组成的方案"）。
                这是规划型智能体的主路径——当目标确实由多个部分组成时就走这里。
                输出：{"needs_planning": true, "steps": ["步骤1", "步骤2", ...]}（2 到 6 个步骤）

            关键原则：
            - 单工具调用绝对不拆成多步。例："读 A 文件并总结" 是单步（B），不是两步。
            - 默认不要把 MEMORY.md / PROFILE.md / 技能文件读取当成独立步骤；仅当用户明确询问偏好、历史决策或长期约束时才加入。
            - 每个步骤必须是可执行动作，不写"思考一下""确认一下"之类的空话。
            - 多部分、多阶段、需要逐步推进的目标走(C)；真正单一原子动作走(B)；只有简单一问一答才用(A)。
            """;

    /**
     * Evidence gate — action signals in the USER GOAL. When triage returns
     * direct_answer (A) but the goal contains any of these, the model almost
     * certainly mis-routed a tool-requiring task; accepting the direct answer
     * would end the turn without ever executing a tool ("复杂任务不执行就停止").
     * <p>
     * The gate is deliberately biased toward executing: a false positive only
     * costs one extra executor pass (which still produces the answer, with or
     * without tools), whereas a false negative silently drops the whole task.
     * Intentionally excludes very common bare temporal words (现在/当前/最新)
     * to avoid downgrading genuine knowledge Q&A on every occurrence.
     */
    private static final java.util.regex.Pattern GOAL_REQUIRES_EXECUTION = java.util.regex.Pattern.compile(
            "读取|读一下|读一份|打开文件|查一下|检索|搜索|联网|下载|上传|抓取"
            + "|记住|记一下|录入|保存|写入|存储|更新|删除|新建|创建|生成|画一[张幅]|画个"
            + "|运行|执行|调用|跑一下|发送|发给|安排|提醒|预约"
            + "|我的(记忆|文件|知识库|偏好|笔记|日程|目标)"
            + "|你(现在|目前)?(挂载|加载|有哪些|支持哪些)|挂载了哪些|你的(技能|工具|MCP|插件)"
            + "|帮我(做|改|查|建|写|发|跑|算|订|定|生成|整理|安排)"
            + "|\\.(java|py|ts|js|vue|md|json|ya?ml|sql|csv|xml|txt|sh)\\b",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * Evidence gate — execution-promise phrasing in the direct answer itself.
     * The model says it WILL act ("我先去读取…", "接下来调用…") rather than
     * actually answering, which means the "direct answer" is really a plan
     * preamble that would terminate before the action runs. Scoped to a verb
     * whitelist so a normal narrative opener like "我来介绍一下杭州" is NOT caught.
     */
    private static final java.util.regex.Pattern ANSWER_PROMISES_ACTION = java.util.regex.Pattern.compile(
            "(我(先|这就|马上|稍后|接下来|现在)?(去|来)?|让我(先|来)?|接下来(我)?(会|要|将|需要)?|正在)"
            + "(读取|读一下|查一下|查询|检索|搜索|联网|调用|执行|运行|获取|访问|查看一下"
            + "|保存|记住|记录|写入|录入|创建|新建|生成|下载|上传|发送)");

    /**
     * Returns true when a triage {@code direct_answer} (A) should be overridden
     * and routed through the executor as a single-step plan instead. Package-
     * private and side-effect free so the gate's regex behavior is unit-testable
     * without mocking the whole node.
     *
     * @param goal         the user goal
     * @param directAnswer the answer the triage model produced (may be null)
     */
    static boolean shouldOverrideDirectAnswer(String goal, String directAnswer) {
        String userAsk = stripInjectedContext(goal);
        boolean goalNeedsExecution = userAsk != null && GOAL_REQUIRES_EXECUTION.matcher(userAsk).find();
        boolean answerPromisesAction = directAnswer != null && ANSWER_PROMISES_ACTION.matcher(directAnswer).find();
        return goalNeedsExecution || answerPromisesAction;
    }

    /**
     * Strips the injected {@code <memory-context>…</memory-context>} wrapper that
     * RuntimeContextInjector prepends to every goal, returning just the user's
     * actual ask. Without this the gate matches on the injected memory/profile
     * text (which contains filenames like {@code user.md} and memory keywords),
     * firing on essentially every task and defeating the direct-answer fast path.
     */
    static String stripInjectedContext(String goal) {
        if (goal == null) {
            return null;
        }
        int end = goal.lastIndexOf("</memory-context>");
        if (end >= 0) {
            return goal.substring(end + "</memory-context>".length()).trim();
        }
        return goal;
    }

    /** Whole injected long-term-memory recall block (any casing). */
    private static final Pattern MEMORY_CONTEXT_BLOCK =
            Pattern.compile("(?is)<\\s*memory-context\\s*>.*?</\\s*memory-context\\s*>");
    /** Stray open/close memory-context fence tags left after block removal. */
    private static final Pattern MEMORY_CONTEXT_TAG =
            Pattern.compile("(?i)</?\\s*memory-context\\s*>");
    /** Marker that introduces the real instruction inside a scheduled-run wrapper. */
    private static final String CRON_TASK_MARKER = "[任务指令]";
    /** Suffix appended by a goal-driven re-plan pass; not part of the user's ask. */
    private static final String FOLLOWUP_MARKER = "[Follow-up guidance]";

    /**
     * Recovers the user's actual request from the fully-assembled agent prompt so
     * the persisted/displayed plan goal reads as the task itself, not the
     * framework scaffolding wrapped around it. The graph receives the goal already
     * enriched — a {@code <memory-context>…</memory-context>} recall block is
     * prepended for every turn, scheduled runs add a wrapper whose real payload
     * sits after {@code [任务指令]}, and a re-plan pass appends a
     * {@code [Follow-up guidance]} block. Persisting that verbatim left the Plan
     * board showing "&lt;memory-context&gt; The following is what you…" instead of
     * the user's goal. Strips, in order: the recall block, the scheduled-run
     * preamble (keeping only the instruction body), and the follow-up suffix.
     * Falls back to the raw goal if scrubbing would leave nothing.
     */
    static String displayGoal(String goal) {
        if (goal == null || goal.isBlank()) {
            return goal == null ? "" : goal;
        }
        String s = MEMORY_CONTEXT_BLOCK.matcher(goal).replaceAll("");
        s = MEMORY_CONTEXT_TAG.matcher(s).replaceAll("");
        int task = s.lastIndexOf(CRON_TASK_MARKER);
        if (task >= 0) {
            s = s.substring(task + CRON_TASK_MARKER.length());
        }
        int followup = s.indexOf(FOLLOWUP_MARKER);
        if (followup >= 0) {
            s = s.substring(0, followup);
        }
        s = s.strip();
        return s.isEmpty() ? goal.strip() : s;
    }

    public PlanGenerationNode(ChatModel chatModel, PlanningService planningService,
                              NodeStreamingChatHelper streamingHelper,
                              ConversationWindowManager conversationWindowManager,
                              AgentToolSet toolSet) {
        this(chatModel, planningService, streamingHelper, conversationWindowManager, toolSet, null, null);
    }

    public PlanGenerationNode(ChatModel chatModel, PlanningService planningService,
                              NodeStreamingChatHelper streamingHelper,
                              ConversationWindowManager conversationWindowManager,
                              AgentToolSet toolSet,
                              GoalService goalService, GoalProperties goalProperties) {
        this(chatModel, planningService, streamingHelper, conversationWindowManager, toolSet,
                goalService, goalProperties, null);
    }

    public PlanGenerationNode(ChatModel chatModel, PlanningService planningService,
                              NodeStreamingChatHelper streamingHelper,
                              ConversationWindowManager conversationWindowManager,
                              AgentToolSet toolSet,
                              GoalService goalService, GoalProperties goalProperties,
                              AgentService agentService) {
        this.chatModel = chatModel;
        this.planningService = planningService;
        this.streamingHelper = streamingHelper;
        this.conversationWindowManager = conversationWindowManager;
        this.toolSet = toolSet;
        this.goalService = goalService;
        this.goalProperties = goalProperties;
        this.agentService = agentService;
    }

    /**
     * @deprecated use the full-parameter constructor instead
     */
    @Deprecated
    public PlanGenerationNode(ChatModel chatModel, PlanningService planningService) {
        this(chatModel, planningService, null, null, null, null, null);
    }

    /**
     * Auto-derive a goal from a freshly-generated multi-step plan so the
     * Plan-Execute path engages the goal subsystem (the planner / step executor
     * never call {@code setGoal} themselves). The plan steps become the goal's
     * acceptance criteria — the plan IS the decomposition — so the first
     * evaluation skips the bootstrap round and judges those criteria directly.
     *
     * <p>Returns the created goal (to inject into {@code ACTIVE_GOAL} so THIS
     * run's GoalEvaluationNode picks it up) or {@code null} when not applicable:
     * feature off, fewer than {@link #MIN_STEPS_FOR_AUTO_GOAL} steps, no channel
     * context, or the conversation already has an active goal. Best-effort —
     * any failure is swallowed so planning is never blocked by goal bookkeeping.
     */
    GoalEntity maybeAutoCreateGoal(PlanStateAccessor accessor, List<String> steps) {
        if (goalService == null || goalProperties == null
                || !goalProperties.isEnabled() || !goalProperties.isAutoGoalFromPlan()) {
            return null;
        }
        if (steps == null || steps.size() < MIN_STEPS_FOR_AUTO_GOAL) {
            return null;
        }
        ChatOrigin origin = accessor.chatOrigin();
        String convId = origin.conversationId();
        if (convId == null || convId.isBlank() || origin.agentId() == null) {
            return null;
        }
        try {
            if (goalService.findActiveByConversation(convId) != null) {
                return null; // respect an existing goal (incl. re-plan passes)
            }
            String request = displayGoal(accessor.goal());
            GoalCreateRequest req = new GoalCreateRequest();
            req.setConversationId(convId);
            req.setAgentId(origin.agentId());
            req.setWorkspaceId(origin.workspaceId() != null ? origin.workspaceId() : 1L);
            req.setTitle(request.isEmpty() ? "多步任务"
                    : request.length() > AUTO_GOAL_TITLE_MAX
                        ? request.substring(0, AUTO_GOAL_TITLE_MAX) : request);
            req.setDescription(request);
            List<GoalCriterion> criteria = steps.stream()
                    .filter(s -> s != null && !s.isBlank())
                    .map(s -> new GoalCriterion("", s.strip(), false, ""))
                    .collect(Collectors.toList());
            if (!criteria.isEmpty()) {
                req.setCriteria(criteria);
            }
            String username = origin.requesterId() != null && !origin.requesterId().isBlank()
                    ? origin.requesterId() : "system";
            GoalEntity created = goalService.create(req, username);
            log.info("[PlanGeneration] Auto-derived goal {} from plan ({} criteria) for conversation {}",
                    created.getId(), criteria.size(), convId);
            return created;
        } catch (Exception e) {
            log.warn("[PlanGeneration] Auto-goal-from-plan skipped (non-fatal): {}", e.toString());
            return null;
        }
    }

    /**
     * Enabled agents in the given workspace, excluding the parent (plan) agent
     * itself — these are the agents a step can be delegated to. Empty when
     * delegation is unavailable (no {@link AgentService}) or no peers exist.
     */
    private List<AgentEntity> listDelegatableAgents(Long workspaceId, String parentAgentId) {
        if (agentService == null || workspaceId == null) {
            return List.of();
        }
        try {
            return agentService.listAgentsByWorkspace(workspaceId, true).stream()
                    .filter(a -> a.getId() != null && !String.valueOf(a.getId()).equals(parentAgentId))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("[PlanGeneration] Failed to list delegatable agents (non-fatal): {}", e.toString());
            return List.of();
        }
    }

    /**
     * Map the planner's {@code step_agents} (agent names, parallel to steps) to
     * agent ids. Returns {@code null} when nothing is delegated so {@code createPlan}
     * stays on the legacy path. Names are matched case-insensitively against the
     * delegatable agents; blank / unknown / parent-agent names resolve to {@code null}
     * (that step runs with the parent agent).
     */
    private List<Long> resolveStepAgents(List<String> steps, List<String> stepAgents,
                                         Long workspaceId, String parentAgentId) {
        if (stepAgents == null || stepAgents.isEmpty() || steps == null || steps.isEmpty()) {
            return null;
        }
        List<AgentEntity> delegatable = listDelegatableAgents(workspaceId, parentAgentId);
        if (delegatable.isEmpty()) {
            return null;
        }
        Map<String, Long> byName = new HashMap<>();
        for (AgentEntity a : delegatable) {
            if (a.getName() != null) {
                byName.put(a.getName().trim().toLowerCase(), a.getId());
            }
        }
        List<Long> ids = new ArrayList<>();
        boolean any = false;
        for (int i = 0; i < steps.size(); i++) {
            String name = i < stepAgents.size() ? stepAgents.get(i) : null;
            Long id = (name == null || name.isBlank()) ? null : byName.get(name.trim().toLowerCase());
            if (id != null) {
                any = true;
            }
            ids.add(id);
        }
        return any ? ids : null;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        PlanStateAccessor accessor = new PlanStateAccessor(state);
        String goal = accessor.goal();

        // Goal follow-up injection: GoalEvaluationNode requested a re-plan
        // pass with extra guidance. The mid-pass plan state was wiped by
        // the previous node, so we run the normal planning flow but
        // append the follow-up prompt to the user goal so the planner
        // sees "do these original objectives + this next step the
        // evaluator just asked for".
        String followupPrompt = state.value(MateClawStateKeys.GOAL_FOLLOWUP_PROMPT, "");
        if (!followupPrompt.isEmpty()) {
            log.info("[PlanGeneration] Goal follow-up active, augmenting goal with {} chars of guidance",
                    followupPrompt.length());
            goal = goal + "\n\n[Follow-up guidance]\n" + followupPrompt;
        }

        String systemPrompt = accessor.systemPrompt();
        // Persist plans under the real agent id (the same key StepExecutionNode
        // reads), NOT the per-run trace id — otherwise mate_plan.agent_id holds a
        // random trace string and listByAgent never matches, leaving the Plan
        // board permanently empty even after plans are generated.
        String agentId = state.value(MateClawStateKeys.AGENT_ID, "");
        String conversationId = accessor.conversationId();

        // The graph's goal carries framework scaffolding (memory recall block,
        // scheduled-run wrapper, follow-up suffix). Persist and display the
        // scrubbed user request so the Plan board shows the actual task; the raw
        // goal still feeds the triage LLM below.
        String persistGoal = displayGoal(goal);

        log.info("[PlanGeneration] Evaluating goal: {}", persistGoal.length() > 100 ? persistGoal.substring(0, 100) + "..." : persistGoal);

        List<GraphEventPublisher.GraphEvent> events = new ArrayList<>();
        events.add(GraphEventPublisher.phase("planning", Map.of("goal", persistGoal)));

        // Replay path: plan is already in state (injected by chatWithReplayStream); skip LLM.
        Long existingPlanId = state.<Long>value(PlanStateKeys.PLAN_ID).orElse(null);
        if (existingPlanId != null) {
            List<String> existingSteps = accessor.planSteps();
            int resumeIndex = accessor.currentStepIndex();
            log.info("[PlanGeneration] Replay mode — reusing plan {} at step {}/{}", existingPlanId, resumeIndex, existingSteps.size());
            return PlanStateAccessor.output()
                    .needsPlanning(true)
                    .planId(existingPlanId)
                    .planSteps(existingSteps)
                    .planValid(true)
                    .currentStepIndex(resumeIndex)
                    .currentPhase("plan_generated")
                    .events(events)
                    .build();
        }

        try {
            // PLANNING_PROMPT is the sole system message; we deliberately do NOT
            // concatenate the agent's full systemPrompt (wiki / skill / memory guidance),
            // which would dilute the triage instructions.
            List<Message> promptMessages = new ArrayList<>();
            promptMessages.add(new SystemMessage(PLANNING_PROMPT));
            String workspaceBasePath = state.value(MateClawStateKeys.WORKSPACE_BASE_PATH, "");
            vip.mate.agent.context.ChatOrigin chatOrigin =
                    state.<vip.mate.agent.context.ChatOrigin>value(MateClawStateKeys.CHAT_ORIGIN)
                            .orElse(vip.mate.agent.context.ChatOrigin.EMPTY);
            String runtimeModelName = state.value(MateClawStateKeys.RUNTIME_MODEL_NAME, "");
            String runtimeProviderId = state.value(MateClawStateKeys.RUNTIME_PROVIDER_ID, "");
            promptMessages.add(new UserMessage(
                    RuntimeContextInjector.buildContextMessage(
                            workspaceBasePath, null, chatOrigin, runtimeModelName, runtimeProviderId)));

            // Advertise available tools so the LLM can recognize when an action is possible,
            // but do NOT force "any tool usage implies multi-step" — single-hop tool use
            // should resolve to a 1-step plan, not a multi-step decomposition.
            if (toolSet != null && !toolSet.callbacks().isEmpty()) {
                String toolNames = toolSet.callbacks().stream()
                        .map(cb -> cb.getToolDefinition().name())
                        .collect(Collectors.joining(", "));
                promptMessages.add(new UserMessage(
                        "可用工具：" + toolNames
                                + "\n单次工具调用应归为单步（B），不要拆成多步。"));
            }

            // Advertise delegatable specialist agents so the planner can assign a
            // multi-step plan's step to a dedicated agent (e.g. a test step to a
            // QA agent, a UI step to a frontend agent). Only fills the step's
            // step_agents slot; unassigned steps stay with the parent agent.
            // Skipped entirely when no peer agents exist in the workspace.
            List<AgentEntity> delegatable = listDelegatableAgents(chatOrigin.workspaceId(), agentId);
            if (!delegatable.isEmpty()) {
                String agentLines = delegatable.stream()
                        .map(a -> "- " + a.getName()
                                + (StringUtils.hasText(a.getDescription()) ? "：" + a.getDescription() : ""))
                        .collect(Collectors.joining("\n"));
                promptMessages.add(new UserMessage(
                        "可委派的专职 Agent（仅当某步骤明显属于其专长时才指派，否则该步骤留空、由你自己执行）：\n"
                                + agentLines
                                + "\n若要委派，在 step_agents 数组对应位置填写 Agent 名称（与 steps 同序、等长）；"
                                + "不委派的步骤填空字符串。多数步骤通常不需要委派。"));
            }

            // Inject working context (rolling conversation summary) so triage respects
            // prior constraints without re-reading full history.
            String workingContext = accessor.workingContext();
            if (!workingContext.isEmpty()) {
                promptMessages.add(new UserMessage(
                        "以下是此前对话中用户提出的约束、说明和上下文，请在分流时参考：\n\n"
                                + workingContext));
            }

            promptMessages.add(new UserMessage("用户目标：" + goal));

            // Append JSON schema hint generated by BeanOutputConverter so the LLM
            // knows the exact expected structure (replaces hand-written schema in PLANNING_PROMPT).
            BeanOutputConverter<TriageResult> converter = new BeanOutputConverter<>(TriageResult.class);
            promptMessages.add(new UserMessage(converter.getFormat()));

            Prompt prompt = new Prompt(promptMessages);

            // Broadcast a lightweight progress token so the frontend shows activity
            // during the silent triage call (typically 1-3 s).
            if (streamingHelper != null) {
                streamingHelper.broadcastProgress(conversationId, "分析中...");
            }

            // Silent streaming call — structured JSON is parsed below; tokens are not forwarded to the client.
            long triageStartMs = System.currentTimeMillis();
            NodeStreamingChatHelper.StreamResult result = streamingHelper.streamCallSilent(
                    chatModel, prompt, conversationId, "plan_generation");

            // Prompt-too-long handling: compact the conversation window and retry once.
            if (result.isPromptTooLong() && conversationWindowManager != null) {
                log.warn("[PlanGeneration] Prompt too long, attempting compaction and retry");
                List<Message> compactedMessages = conversationWindowManager.compactForRetry(
                        promptMessages.subList(1, promptMessages.size()));
                if (compactedMessages != null) {
                    List<Message> retryMessages = new ArrayList<>();
                    retryMessages.add(promptMessages.get(0));
                    retryMessages.addAll(compactedMessages);
                    result = streamingHelper.streamCallSilent(
                            chatModel, new Prompt(retryMessages), conversationId, "plan_generation_compact_retry");
                }
            }

            long triageMs = System.currentTimeMillis() - triageStartMs;

            String llmResponse = result.text();
            log.info("[PlanGeneration] Triage completed in {}ms", triageMs);
            log.debug("[PlanGeneration] LLM response: {}", llmResponse);

            // D-6: emit triage perf summary
            events.add(GraphEventPublisher.perfSummary("triage", Map.of(
                    "triage_ms", triageMs,
                    "prompt_tokens", result.promptTokens(),
                    "completion_tokens", result.completionTokens()
            )));

            TriageResult triage = converter.convert(llmResponse);
            boolean needsPlanning = triage != null && triage.needsPlanning();

            if (!needsPlanning) {
                // Category (A): direct answer — push to client and terminate via DirectAnswerNode.
                String directAnswer = triage != null && triage.directAnswer() != null
                        ? triage.directAnswer() : llmResponse;

                // Evidence gate: catch a mis-routed A that actually needs tools.
                // Downgrading to a single-step plan keeps tool access; the cost of
                // a false positive is one extra executor pass, while a missed
                // misroute drops the whole task silently.
                if (shouldOverrideDirectAnswer(goal, directAnswer)) {
                    log.warn("[PlanGeneration] Evidence gate overrode direct-answer route; "
                            + "downgrading to single-step plan so tools can execute (goal: {})",
                            goal.length() > 60 ? goal.substring(0, 60) + "..." : goal);
                    List<String> gatedSteps = List.of(persistGoal);
                    var gatedPlan = planningService.createPlan(agentId, conversationId, persistGoal, gatedSteps);
                    events.add(GraphEventPublisher.planCreated(gatedPlan.getId(), gatedSteps));
                    return PlanStateAccessor.output()
                            .needsPlanning(true)
                            .planId(gatedPlan.getId())
                            .planSteps(gatedSteps)
                            .planValid(true)
                            .currentStepIndex(0)
                            .currentPhase("plan_generated")
                            .thinkingStreamed(!result.thinking().isEmpty())
                            .mergeUsage(state, result)
                            .events(events)
                            .build();
                }

                log.info("[PlanGeneration] Direct-answer route taken (no tools, no planning)");

                streamingHelper.broadcastContent(conversationId, directAnswer);

                return PlanStateAccessor.output()
                        .needsPlanning(false)
                        .directAnswer(directAnswer)
                        .currentPhase("direct_answer")
                        .contentStreamed(true)
                        .thinkingStreamed(!result.thinking().isEmpty())
                        .mergeUsage(state, result)
                        .events(events)
                        .build();
            }

            // Categories (B) single-step or (C) multi-step: extract steps.
            List<String> steps = triage != null ? triage.steps() : null;
            if (steps == null || steps.isEmpty()) {
                // LLM asked for planning but produced no steps — fall back to a
                // synthetic 1-step plan using the user's goal so the executor
                // can still reach the tools. (Previous behavior dropped back to
                // direct_answer, which silently stripped tool capability.)
                log.warn("[PlanGeneration] needs_planning=true with empty steps; falling back to single-step plan");
                steps = List.of(persistGoal);
            }

            // Resolve any per-step agent delegation the planner asked for. Null
            // when nothing is delegated, keeping createPlan on the legacy path.
            List<Long> stepAgentIds = resolveStepAgents(steps,
                    triage != null ? triage.stepAgents() : null,
                    chatOrigin.workspaceId(), agentId);
            var plan = planningService.createPlan(agentId, conversationId, persistGoal, steps, stepAgentIds);
            log.info("[PlanGeneration] Plan created: id={}, steps={} ({}){}",
                    plan.getId(), steps.size(), steps.size() == 1 ? "single-step" : "multi-step",
                    stepAgentIds != null ? ", per-step delegation=" + stepAgentIds : "");

            events.add(GraphEventPublisher.planCreated(plan.getId(), steps));

            // Auto-derive a goal from a genuine multi-step plan so the
            // Plan-Execute path engages the goal subsystem. Injected into
            // ACTIVE_GOAL so this same run's GoalEvaluationNode evaluates it.
            GoalEntity autoGoal = maybeAutoCreateGoal(accessor, steps);
            if (autoGoal != null && goalService != null) {
                // Surface it to the UI exactly like the setGoal tool does
                // ({goalId, conversationId, goal}) so the goal panel hydrates
                // even though the user never called setGoal. Same SSE event the
                // frontend goal store already listens for.
                events.add(new GraphEventPublisher.GraphEvent("goal_created", Map.of(
                        "goalId", String.valueOf(autoGoal.getId()),
                        "conversationId", conversationId,
                        "goal", goalService.toResponse(autoGoal)), System.currentTimeMillis()));
            }

            PlanStateAccessor.OutputBuilder planOut = PlanStateAccessor.output()
                    .needsPlanning(true)
                    .planId(plan.getId())
                    .planSteps(steps)
                    .planValid(true)
                    .currentStepIndex(0)
                    .currentPhase("plan_generated")
                    .contentStreamed(true)
                    .thinkingStreamed(!result.thinking().isEmpty())
                    .mergeUsage(state, result)
                    .events(events);
            if (autoGoal != null) {
                planOut.put(MateClawStateKeys.ACTIVE_GOAL, autoGoal);
            }
            return planOut.build();

        } catch (Exception e) {
            log.error("[PlanGeneration] Triage failed, falling back to single-step plan: {}", e.getMessage(), e);
            // When the triage LLM fails or returns unparseable output we now fall back to
            // a single-step plan (the user's goal verbatim) instead of a direct text
            // answer. This preserves tool access on the failure path; the previous
            // "direct answer" fallback silently degraded tool-requiring tasks.
            try {
                var plan = planningService.createPlan(agentId, conversationId, persistGoal, List.of(persistGoal));
                events.add(GraphEventPublisher.planCreated(plan.getId(), List.of(persistGoal)));
                return PlanStateAccessor.output()
                        .needsPlanning(true)
                        .planId(plan.getId())
                        .planSteps(List.of(persistGoal))
                        .planValid(true)
                        .currentStepIndex(0)
                        .currentPhase("plan_generated")
                        .events(events)
                        .build();
            } catch (Exception persistErr) {
                log.error("[PlanGeneration] Single-step fallback persistence also failed: {}", persistErr.getMessage());
                return PlanStateAccessor.output()
                        .needsPlanning(false)
                        .directAnswer("抱歉，我暂时无法完成任务分流，请重试或换一种方式描述任务。")
                        .currentPhase("direct_answer")
                        .events(events)
                        .build();
            }
        }
    }

}
