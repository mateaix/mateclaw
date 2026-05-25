package vip.mate.agent.graph.state;

/**
 * MateClaw 增强版状态键常量
 * <p>
 * 包含原 ReActStateKeys 的所有字段，并新增 summarizing、超限处理、
 * 观察压缩等字段，支撑完整的标准 ReAct 状态图。
 * <p>
 * 所有节点和路由统一引用此类，避免字符串散落。
 *
 * @author MateClaw Team
 */
public final class MateClawStateKeys {

    private MateClawStateKeys() {
    }

    // ===== 输入 =====
    public static final String USER_MESSAGE = "user_message";
    public static final String CONVERSATION_ID = "conversation_id";
    public static final String SYSTEM_PROMPT = "system_prompt";
    public static final String AGENT_ID = "agent_id";
    /** 工作区活动目录（为空不限制文件访问范围） */
    public static final String WORKSPACE_BASE_PATH = "workspace_base_path";

    // ===== 消息列表（APPEND 策略）=====
    public static final String MESSAGES = "messages";

    // ===== 迭代控制（REPLACE 策略）=====
    public static final String CURRENT_ITERATION = "current_iteration";
    public static final String MAX_ITERATIONS = "max_iterations";

    // ===== 工具调用（REPLACE 策略）=====
    public static final String TOOL_CALLS = "tool_calls";
    public static final String TOOL_RESULTS = "tool_results";

    // ===== 控制流（REPLACE 策略）=====
    public static final String FINAL_ANSWER = "final_answer";
    public static final String NEEDS_TOOL_CALL = "needs_tool_call";
    public static final String ERROR = "error";

    // ===== 节点名称（基础）=====
    public static final String REASONING_NODE = "reasoning";
    public static final String ACTION_NODE = "action";
    public static final String OBSERVATION_NODE = "observation";

    // ===== 观察历史（APPEND 策略）=====
    /** 每轮工具调用的处理后观察记录，由 ObservationProcessor 输出 */
    public static final String OBSERVATION_HISTORY = "observation_history";

    // ===== Summarizing 相关（REPLACE 策略）=====
    /** 经过 SummarizingNode 压缩后的上下文 */
    public static final String SUMMARIZED_CONTEXT = "summarized_context";

    /** 最终回答草稿（由 summarizing 或 limitExceeded 节点生成） */
    public static final String FINAL_ANSWER_DRAFT = "final_answer_draft";

    /** 是否需要进入 summarizing 阶段 */
    public static final String SHOULD_SUMMARIZE = "should_summarize";

    // ===== 终止控制（REPLACE 策略）=====
    /** 终止原因，{@link FinishReason#getValue()} */
    public static final String FINISH_REASON = "finish_reason";

    /** 是否已超过最大迭代次数 */
    public static final String LIMIT_EXCEEDED = "limit_exceeded";

    // ===== 统计与追踪（REPLACE 策略）=====
    /** 累计工具调用次数 */
    public static final String TOOL_CALL_COUNT = "tool_call_count";

    /** 累计错误次数 */
    public static final String ERROR_COUNT = "error_count";

    /** 本次对话的追踪 ID */
    public static final String TRACE_ID = "trace_id";

    // ===== 节点名称（新增）=====
    public static final String SUMMARIZING_NODE = "summarizing";
    public static final String FINAL_ANSWER_NODE = "final_answer_node";
    public static final String LIMIT_EXCEEDED_NODE = "limit_exceeded";

    // ===== 事件流（APPEND 策略）=====
    public static final String PENDING_EVENTS = "pending_events";

    /**
     * Multimodal routing decision for the current turn (REPLACE strategy).
     * Stored as a Map ready for JSON serialization. Set by BaseAgent before
     * the reasoning node runs; read back by FinalAnswerNode and (separately)
     * emitted as a graph event for the SSE accumulator to write into the
     * persisted message metadata under {@code metadata.routing}.
     */
    public static final String ROUTING_DECISION = "routing_decision";

    // ===== 阶段标记（REPLACE 策略）=====
    public static final String CURRENT_PHASE = "current_phase";

    // ===== Thinking（REPLACE 策略）=====
    /** 最终完整 thinking（由 FinalAnswerNode 或直接回答路径聚合） */
    public static final String FINAL_THINKING = "final_thinking";

    /** 当前节点的完整 thinking（节点结束时写入） */
    public static final String CURRENT_THINKING = "current_thinking";

    // ===== 流式防重（REPLACE 策略）=====
    /** 当前节点的 content 是否已通过 streaming helper 实时推送 */
    public static final String CONTENT_STREAMED = "content_streamed";

    /** 当前节点的 thinking 是否已通过 streaming helper 实时推送 */
    public static final String THINKING_STREAMED = "thinking_streamed";

    // ===== 流式内容暂存（REPLACE 策略）=====
    /** ReasoningNode 流式推送后暂存的文本内容，供 AWAITING_APPROVAL 路径持久化使用 */
    public static final String STREAMED_CONTENT = "streamed_content";
    /** ReasoningNode 流式推送后暂存的 thinking 内容，供 AWAITING_APPROVAL 路径持久化使用 */
    public static final String STREAMED_THINKING = "streamed_thinking";

    // ===== 审批控制（REPLACE 策略）=====
    /** 当 ActionNode 遇到需要审批的工具时设为 true，ObservationDispatcher 据此终止 Graph */
    public static final String AWAITING_APPROVAL = "awaiting_approval";

    // ===== 审批重放（REPLACE 策略）=====
    /** 预批准的工具调用 JSON，由 chatWithReplay 注入，ReasoningNode 检测后跳过 LLM 直接发出 */
    public static final String FORCED_TOOL_CALL = "forced_tool_call";

    /**
     * Plan-Execute replay 专用：审批通过的工具调用 payload（工具名+参数），
     * 由 StateGraphPlanExecuteAgent.chatWithReplayStream 注入，
     * StepExecutionNode 检测到匹配时跳过 ToolGuard 直接执行。
     */
    public static final String PRE_APPROVED_TOOL_CALL = "pre_approved_tool_call";

    // ===== 请求者身份（REPLACE 策略）=====
    /** 原始请求者 ID（IM senderId / Web Authentication.getName()），用于审批身份校验 */
    public static final String REQUESTER_ID = "requester_id";

    // ===== 取消控制（REPLACE 策略）=====
    /** 取消标志：外部请求停止时设为 true，各节点在入口处检查 */
    public static final String STOP_REQUESTED = "stop_requested";

    // ===== LLM 调用计数（REPLACE 策略）=====
    /** 累计 LLM 调用次数（每次 ReasoningNode 调用 LLM 时递增，独立于迭代计数） */
    public static final String LLM_CALL_COUNT = "llm_call_count";

    // ===== Token Usage 累计（REPLACE 策略，节点内累加后写回）=====
    public static final String PROMPT_TOKENS = "prompt_tokens";
    public static final String COMPLETION_TOKENS = "completion_tokens";

    // ===== 运行时模型快照（REPLACE 策略，buildInitialState 注入）=====
    public static final String RUNTIME_MODEL_NAME = "runtime_model_name";
    public static final String RUNTIME_PROVIDER_ID = "runtime_provider_id";

    // ===== RFC-052: Tool returnDirect 与数据隔离 =====

    /**
     * RFC-052: when true the latest tool batch contained at least one tool
     * declared as returnDirect, so the graph must short-circuit to
     * {@link #FINAL_ANSWER_NODE} without re-entering the LLM.
     */
    public static final String RETURN_DIRECT_TRIGGERED = "return_direct_triggered";

    /**
     * RFC-052: list of {@code DirectToolOutput} accumulated from the most recent
     * tool batch, used by FinalAnswerNode to assemble the final answer.
     */
    public static final String DIRECT_TOOL_OUTPUTS = "direct_tool_outputs";

    /** Source references observed from successful tool results during this run. */
    public static final String SOURCE_EVIDENCE_LEDGER = "source_evidence_ledger";

    // ===== Persistent goal — cross-turn objective lock-in =====

    /**
     * Active goal snapshot bound to the conversation; null when no goal.
     * Injected by {@code buildInitialState} from {@code GoalService.findActiveByConversation}.
     * Read by GoalEvaluationNode + its dispatcher.
     */
    public static final String ACTIVE_GOAL = "active_goal";

    /**
     * Map snapshot of the latest evaluation pass (score/gap/decision/...).
     * Written by GoalEvaluationNode; consumed by the SSE accumulator for
     * the {@code goal_evaluated} event payload.
     */
    public static final String GOAL_EVALUATION_RESULT = "goal_evaluation_result";

    /**
     * True when GoalEvaluationNode injected a follow-up prompt and the
     * dispatcher should re-enter the reasoning loop (or PlanGeneration in
     * the Plan-Execute graph) instead of terminating to END.
     */
    public static final String GOAL_FOLLOWUP_INJECTED = "goal_followup_injected";

    /**
     * Follow-up user-message text to append to MESSAGES on graph re-entry.
     * ReasoningNode (or PlanGenerationNode) reads this on its way in,
     * appends to MESSAGES, then clears the value so the second pass
     * cannot double-inject.
     */
    public static final String GOAL_FOLLOWUP_PROMPT = "goal_followup_prompt";

    /**
     * Re-entry guard for TERMINAL evaluation passes: GoalEvaluationNode sets
     * this true only when it ENDS the run (completed / exhausted / skip /
     * continue-without-followup). The FinalAnswerNode→GoalEvaluation edge skips
     * re-entering once it's true. The followup branch deliberately leaves it
     * false so the self-continuation loop can re-evaluate the next answer; that
     * loop is bounded instead by {@link #GOAL_FOLLOWUP_COUNT} (per-run cap) plus
     * the goal's turn / LLM-call budgets.
     */
    public static final String GOAL_EVALUATED_THIS_RUN = "goal_evaluated_this_run";

    /**
     * Number of auto-followups already injected in THIS graph run (one user
     * turn). Bounds the self-continuation loop per single message — independent
     * of the goal's cross-turn turn_budget — so one message can't drive an
     * unbounded number of autonomous steps or exhaust the graph recursion
     * limit. Implicitly 0 at the start of each graph invocation.
     */
    public static final String GOAL_FOLLOWUP_COUNT = "goal_followup_count";

    /**
     * Cumulative agent LLM-call count already billed to the goal in THIS graph
     * run. The run-to-completion loop evaluates multiple times per run while
     * {@link #LLM_CALL_COUNT} keeps growing; recording only
     * (current − accounted) on each pass avoids re-billing earlier calls and
     * exhausting the goal's LLM budget prematurely. Implicitly 0 at run start.
     */
    public static final String GOAL_ACCOUNTED_LLM_CALL_COUNT = "goal_accounted_llm_call_count";

    /** Graph-node identifier for the GoalEvaluationNode. */
    public static final String GOAL_EVALUATION_NODE = "goal_evaluation";

    // ===== RFC-063r: ChatOrigin propagation through the StateGraph =====

    /**
     * RFC-063r §2.5: top-level agent writes the {@code ChatOrigin} value object
     * into graph state once at {@code buildInitialState}; nodes (especially
     * {@code StepExecutionNode} in the Plan-Execute sub-graph) read it
     * read-only when invoking {@link vip.mate.agent.graph.executor.ToolExecutionExecutor}
     * so child graphs and delegated agents inherit the originating channel /
     * workspace context.
     */
    public static final String CHAT_ORIGIN = "chat_origin";

    // ===== Skill progressive disclosure (REPLACE strategy) =====

    /**
     * Names of skills explicitly loaded via the {@code load_skill} tool during
     * this graph run. Stored as a {@code Set<String>} and used to pin recently
     * loaded skills to the top of the runtime skill catalog so a multi-iteration
     * loop stops re-loading the same skill it already pulled into message
     * history. ActionNode reads the prior value and writes back the merged set
     * (read-merge-write under the REPLACE strategy).
     * <p>
     * MUST be registered in both the ReAct and Plan-Execute KeyStrategyFactory
     * blocks or the framework will drop it on multi-node merges, leaving the
     * catalog ranker blind to in-run loads.
     */
    public static final String LOADED_SKILLS = "loaded_skills";

    /**
     * Function names of extension-tier tools activated via {@code enable_tool}
     * during this run. Stored as a {@code Set<String>}; ReasoningNode adds these
     * back to the active tool callbacks on its next turn so an enabled extension
     * tool becomes callable within the same ReAct loop. ActionNode reads the
     * prior value and writes back the merged set (read-merge-write under REPLACE).
     * <p>
     * MUST be registered in both KeyStrategyFactory blocks (see
     * {@link #LOADED_SKILLS}).
     */
    public static final String ENABLED_EXTENSION_TOOLS = "enabled_extension_tools";
}
