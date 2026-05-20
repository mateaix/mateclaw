package vip.mate.llm.model;

/**
 * Embedding 模型协议。
 * <p>
 * 与 {@link ModelProtocol}（Chat 协议）分离——chat 和 embedding 在同一个 Provider 下
 * 可能走不同的请求格式：
 * <ul>
 *   <li>DashScope 的 embedding endpoint 是专用 path
 *       （/api/v1/services/embeddings/text-embedding/text-embedding）</li>
 *   <li>OpenAI 兼容协议的 embedding 统一走 /v1/embeddings</li>
 * </ul>
 * <p>
 * Routing happens in {@code EmbeddingModelFactory.resolveEmbeddingProtocol}
 * via the {@code chatModel} column on {@link ModelProviderEntity}, matching
 * {@link ModelProtocol#fromChatModel}.
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
}
