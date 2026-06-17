package vip.mate.agent.graph.plan.state;

/**
 * Plan-Execute 特有的状态键常量
 * <p>
 * 共享键（如 PENDING_EVENTS、CURRENT_PHASE）直接引用 {@link vip.mate.agent.graph.state.MateClawStateKeys}，
 * 不在此处重复定义。
 *
 * @author MateClaw Team
 */
public final class PlanStateKeys {

    private PlanStateKeys() {}

    // ===== 输入 =====
    public static final String GOAL = "goal";

    // ===== 计划 =====
    public static final String PLAN_ID = "plan_id";
    public static final String PLAN_STEPS = "plan_steps";               // List<String>
    public static final String PLAN_VALID = "plan_valid";
    public static final String NEEDS_PLANNING = "needs_planning";       // boolean

    // ===== 步骤控制 =====
    public static final String CURRENT_STEP_INDEX = "current_step_index";
    public static final String CURRENT_STEP_TITLE = "current_step_title";
    public static final String CURRENT_STEP_RESULT = "current_step_result";
    public static final String COMPLETED_RESULTS = "completed_results"; // APPEND 策略

    /**
     * Number of re-plans performed in THIS graph run (REPLACE strategy). When a
     * step throws, the executor re-plans the remaining work around the failure
     * (carried in {@link #WORKING_CONTEXT}) instead of aborting outright, up to
     * a small bound — this counter enforces that bound so a pathological failure
     * loop can't re-plan forever. Implicitly 0 at run start.
     */
    public static final String PLAN_REPLAN_COUNT = "plan_replan_count";

    // ===== 终止 =====
    public static final String FINAL_SUMMARY = "final_summary";
    public static final String DIRECT_ANSWER = "direct_answer";         // 简单问答的直接回答

    // ===== 上下文 =====
    /**
     * 工作上下文 / 摘要上下文（REPLACE 策略）
     * <p>
     * 保存对 conversation history + 已完成步骤结果的压缩摘要，
     * 供 StepExecutionNode / PlanSummaryNode 使用，避免 prompt 无限膨胀。
     */
    public static final String WORKING_CONTEXT = "working_context";

    // ===== Thinking =====
    /** 汇总阶段的完整 thinking */
    public static final String FINAL_SUMMARY_THINKING = "final_summary_thinking";

    /** 当前步骤的完整 thinking */
    public static final String CURRENT_STEP_THINKING = "current_step_thinking";

    // ===== 节点名称 =====
    public static final String PLAN_GENERATION_NODE = "plan_generation";
    public static final String STEP_EXECUTION_NODE = "step_execution";
    public static final String PLAN_SUMMARY_NODE = "plan_summary";
    public static final String DIRECT_ANSWER_NODE = "direct_answer_node";

    // 注意：PENDING_EVENTS 直接使用 MateClawStateKeys.PENDING_EVENTS，不在此重复定义
}
