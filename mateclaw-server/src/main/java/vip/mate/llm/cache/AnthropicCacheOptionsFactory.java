package vip.mate.llm.cache;

import org.springframework.ai.anthropic.api.AnthropicCacheOptions;
import org.springframework.ai.anthropic.api.AnthropicCacheStrategy;
import org.springframework.ai.anthropic.api.AnthropicCacheTtl;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * 把 MateClaw 的 {@link CacheProperties} 翻译成 spring-ai 1.1.4 一等支持的 {@link AnthropicCacheOptions}。
 *
 * <p>设计说明：spring-ai 已内置完整的 Anthropic prompt cache 框架（{@code CacheEligibilityResolver} +
 * {@code CacheBreakpointTracker}），它会按 {@link AnthropicCacheStrategy} 自动决策在 system / tools /
 * conversation history 上挂 {@code cache_control}。我们只需配置好策略与 TTL/min-length 即可，
 * 不必自己写 HTTP body 拦截器。</p>
 *
 * <p>映射规则（对应 RFC-014 Change 1 的 SystemAndTailCacheStrategy 默认行为）：
 * <ul>
 *   <li>{@code includeToolsBlock=true} → {@link AnthropicCacheStrategy#CONVERSATION_HISTORY}（system + tools + 最新一段对话；覆盖最完整）</li>
 *   <li>{@code includeToolsBlock=false} → {@link AnthropicCacheStrategy#SYSTEM_ONLY}</li>
 *   <li>{@code ttl=extended-1h} → SYSTEM/USER 两个消息类型映射为 {@link AnthropicCacheTtl#ONE_HOUR}</li>
 *   <li>{@code minPromptTokens} → 转字符长度（× 4，粗粒度估算）作为 SYSTEM 段的 min content length</li>
 * </ul></p>
 *
 * <p>无状态、线程安全。</p>
 */
@Component
public class AnthropicCacheOptionsFactory {

    /** token → 字符 的粗略乘子（英文 4 字符 ≈ 1 token；中文略低，但作为门槛足矣）。 */
    private static final int CHARS_PER_TOKEN_HEURISTIC = 4;

    private final CacheProperties props;

    public AnthropicCacheOptionsFactory(CacheProperties props) {
        this.props = props;
    }

    /**
     * 构建启用了缓存的 {@link AnthropicCacheOptions}；当 {@link CacheProperties#isEnabled()} 为 false
     * 时返回 {@link AnthropicCacheOptions#DISABLED}（spring-ai 内置的 NONE 策略快速路径）。
     */
    public AnthropicCacheOptions build() {
        if (!props.isEnabled()) {
            return AnthropicCacheOptions.DISABLED;
        }

        AnthropicCacheStrategy strategy = props.isIncludeToolsBlock()
                ? AnthropicCacheStrategy.CONVERSATION_HISTORY
                : AnthropicCacheStrategy.SYSTEM_ONLY;

        AnthropicCacheTtl ttl = (props.resolveCacheTtl() == CacheTtl.EXTENDED_1H)
                ? AnthropicCacheTtl.ONE_HOUR
                : AnthropicCacheTtl.FIVE_MINUTES;

        Map<MessageType, AnthropicCacheTtl> ttlMap = new EnumMap<>(MessageType.class);
        ttlMap.put(MessageType.SYSTEM, ttl);
        ttlMap.put(MessageType.USER, ttl);

        Map<MessageType, Integer> minLenMap = new EnumMap<>(MessageType.class);
        int sysMinChars = Math.max(256, props.getMinPromptTokens() * CHARS_PER_TOKEN_HEURISTIC / 8);
        minLenMap.put(MessageType.SYSTEM, sysMinChars);

        return AnthropicCacheOptions.builder()
                .strategy(strategy)
                .messageTypeTtl(ttlMap)
                .messageTypeMinContentLengths(minLenMap)
                .multiBlockSystemCaching(false)
                .build();
    }
}
