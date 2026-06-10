package vip.mate.llm.model;

/**
 * 模型族分类 — 收敛所有 provider/model 差异到统一的参数适配策略。
 * <p>
 * 按 provider+model 维度管理生成参数，
 * 将 MateClaw 中散落在 isThinkingModel / requiresFixedTemperatureOne 等多处的判断
 * 收敛到一个 enum，每个族声明自己的参数约束。
 * <p>
 * 约束维度：
 * <ul>
 *   <li>useMaxCompletionTokens — 是否必须用 max_completion_tokens 替代 max_tokens</li>
 *   <li>suppressMaxTokens — 是否禁止发送 max_tokens</li>
 *   <li>supportsReasoningEffort — 是否支持 reasoning_effort 参数</li>
 *   <li>fixedTemperatureOne — 是否强制 temperature=1.0</li>
 *   <li>suppressTopP — 是否禁止发送 top_p</li>
 *   <li>thinking — 是否为 thinking/reasoning 模型（影响 reasoningContent patch）</li>
 * </ul>
 *
 * @author MateClaw Team
 */
public enum ModelFamily {

    /**
     * OpenAI reasoning 模型：gpt-5*, o1*, o3*, o4*
     * <p>
     * 约束：禁 max_tokens，必须 max_completion_tokens；支持 reasoning_effort；
     * 强制 temperature=1.0；禁 top_p。
     */
    OPENAI_REASONING(true, true, true, true, true, true),

    /**
     * Kimi thinking 模型：kimi-k2* 系列
     * <p>
     * 约束：保留 max_tokens（Moonshot API 兼容旧格式）；不支持 reasoning_effort（会报参数不识别）；
     * 强制 temperature=1.0；禁 top_p。
     * 注意：kimi-k2.5 天然开启 thinking，kimi-k2-thinking* 显式 thinking。
     */
    KIMI_THINKING(false, false, false, true, true, true),

    /**
     * DeepSeek reasoning 模型：deepseek-reasoner
     * <p>
     * 约束：保留 max_tokens（DeepSeek API 兼容）；不支持 reasoning_effort；
     * temperature 固定 1.0（DeepSeek reasoner 约束）；禁 top_p。
     */
    DEEPSEEK_REASONER(false, false, false, true, true, true),

    /**
     * DeepSeek V4 reasoning 模型：deepseek-v4-flash / deepseek-v4-pro。
     * <p>
     * 与 {@link #DEEPSEEK_REASONER}（v3.2）的关键差异：V4 接受 {@code reasoning_effort} 字段，
     * 也不强制 temperature=1。OpenClaw 实现参考 {@code extensions/deepseek/models.ts:28-81} 标记
     * {@code supportsReasoningEffort: true}。<br>
     * 约束：保留 max_tokens；支持 reasoning_effort；temperature/topP 用配置值。
     * thinking=true 让 {@link vip.mate.llm.chatmodel.DeepSeekV4ThinkingDecorator}
     * 在请求体注入 OpenAI 协议外的 {@code thinking: {type: enabled|disabled}} 字段。
     */
    DEEPSEEK_V4_REASONING(false, false, true, false, false, true),

    /**
     * Xiaomi MiMo thinking 模型：MiMo-VL-*、mimo-* 系列。
     * <p>
     * MiMo 的 thinking 模式与 DeepSeek 类似：响应返回 {@code reasoning_content}，
     * 后续多轮请求必须将 {@code reasoning_content} 传回，否则 API 返回 400 错误。
     * 约束：保留 max_tokens；不支持 reasoning_effort；temperature/topP 用配置值。
     */
    MIMO_THINKING(false, false, false, false, false, true),

    /**
     * 通用 thinking 模型（名称含 "thinking" 或 "reasoner" 但不匹配上述族）：
     * 如 qwen3-235b-a22b-thinking-2507
     * <p>
     * 约束：保留 max_tokens；不支持 reasoning_effort（保守策略）；
     * temperature/topP 使用配置值。
     */
    GENERIC_THINKING(false, false, false, false, false, true),

    /**
     * 标准模型：所有不匹配上述族的模型
     * <p>
     * 无特殊约束，全部参数正常传递。
     */
    STANDARD(false, false, false, false, false, false);

    private final boolean useMaxCompletionTokens;
    private final boolean suppressMaxTokens;
    private final boolean supportsReasoningEffort;
    private final boolean fixedTemperatureOne;
    private final boolean suppressTopP;
    private final boolean thinking;

    ModelFamily(boolean useMaxCompletionTokens, boolean suppressMaxTokens,
                boolean supportsReasoningEffort, boolean fixedTemperatureOne,
                boolean suppressTopP, boolean thinking) {
        this.useMaxCompletionTokens = useMaxCompletionTokens;
        this.suppressMaxTokens = suppressMaxTokens;
        this.supportsReasoningEffort = supportsReasoningEffort;
        this.fixedTemperatureOne = fixedTemperatureOne;
        this.suppressTopP = suppressTopP;
        this.thinking = thinking;
    }

    /** 是否必须用 max_completion_tokens 替代 max_tokens */
    public boolean useMaxCompletionTokens() {
        return useMaxCompletionTokens;
    }

    /** 是否禁止发送 max_tokens */
    public boolean suppressMaxTokens() {
        return suppressMaxTokens;
    }

    /** 是否支持 reasoning_effort 参数 */
    public boolean supportsReasoningEffort() {
        return supportsReasoningEffort;
    }

    /** 是否强制 temperature=1.0 */
    public boolean fixedTemperatureOne() {
        return fixedTemperatureOne;
    }

    /** 是否禁止发送 top_p */
    public boolean suppressTopP() {
        return suppressTopP;
    }

    /** 是否为 thinking/reasoning 模型 */
    public boolean isThinking() {
        return thinking;
    }

    /**
     * 根据模型名称检测所属模型族。
     * <p>
     * 匹配优先级：精确族 > 通用 thinking > 标准。
     *
     * @param modelName 模型名称（如 "gpt-5.2", "kimi-k2.5", "deepseek-reasoner"）
     * @return 对应的 ModelFamily
     */
    public static ModelFamily detect(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return STANDARD;
        }
        String normalized = modelName.trim().toLowerCase();

        // OpenAI reasoning 族：gpt-5*, o1*, o3*, o4*
        if (normalized.startsWith("gpt-5")
                || normalized.startsWith("o1")
                || normalized.startsWith("o3")
                || normalized.startsWith("o4")) {
            return OPENAI_REASONING;
        }

        // Kimi thinking 族：kimi-k2* 全系列 + kimi-for-coding（底层为 kimi-k2.5）
        if (normalized.startsWith("kimi-k2") || normalized.equals("kimi-for-coding")) {
            return KIMI_THINKING;
        }

        // DeepSeek V4 reasoning 族：deepseek-v4-flash / deepseek-v4-pro
        // 优先匹配（在 DEEPSEEK_REASONER 之前），因为两者都含 "deepseek" 前缀但 V4 接受 reasoning_effort。
        if (normalized.equals("deepseek-v4-flash") || normalized.equals("deepseek-v4-pro")) {
            return DEEPSEEK_V4_REASONING;
        }

        // DeepSeek reasoning 族：仅 deepseek-reasoner
        if (normalized.equals("deepseek-reasoner")) {
            return DEEPSEEK_REASONER;
        }

        // Xiaomi MiMo thinking 族：mimo-* / MiMo-VL-* 系列
        if (normalized.startsWith("mimo")) {
            return MIMO_THINKING;
        }

        // 通用 thinking 族：名称含 thinking / reasoner 关键词
        if (normalized.contains("thinking") || normalized.contains("reasoner")) {
            return GENERIC_THINKING;
        }

        return STANDARD;
    }
}
