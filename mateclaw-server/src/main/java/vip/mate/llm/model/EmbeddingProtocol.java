package vip.mate.llm.model;

/**
 * Embedding 模型协议。
 * <p>
 * 与 {@link ModelProtocol}（Chat 协议）分离——chat 和 embedding 在同一个 Provider 下
 * 可能走不同的请求格式：
 * <ul>
 *   <li>DashScope 的 embedding endpoint 是专用 path（/api/v1/services/embeddings/text-embedding/text-embedding）</li>
 *   <li>OpenAI 兼容协议的 embedding 统一走 /v1/embeddings</li>
 * </ul>
 * <p>
 * <b>注意</b>：{@link EmbeddingModelFactory} 现在优先通过 {@code chatModel} 列判断协议
 * （与 {@link ModelProtocol#fromChatModel} 保持一致），{@link #fromProviderId} 已不再使用。
 * 保留该方法仅作参考；不要在新代码中调用它。
 *
 * @author MateClaw Team
 */
public enum EmbeddingProtocol {

    DASHSCOPE_EMBEDDING("dashscope-embedding"),
    OPENAI_EMBEDDING("openai-embedding");

    private final String id;

    EmbeddingProtocol(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }

    /**
     * 从 providerId 推断 embedding 协议。
     * <ul>
     *   <li>dashscope / 任何包含 "dashscope" / "qwen" / "aliyun" 的 → DASHSCOPE_EMBEDDING</li>
     *   <li>其他（openai / deepseek / kimi / zhipu / moonshot / 任何 OpenAI 兼容） → OPENAI_EMBEDDING</li>
     * </ul>
     */
    public static EmbeddingProtocol fromProviderId(String providerId) {
        if (providerId == null) return OPENAI_EMBEDDING;
        String p = providerId.toLowerCase().trim();
        if (p.contains("dashscope") || p.contains("qwen") || p.contains("aliyun")) {
            return DASHSCOPE_EMBEDDING;
        }
        return OPENAI_EMBEDDING;
    }
}
