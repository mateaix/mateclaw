package vip.mate.llm.cache;

import org.springframework.ai.chat.metadata.Usage;

import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 从 Spring AI {@link Usage} 中提取各 provider 的 prompt cache / reasoning token 计数。
 *
 * <p>spring-ai 的高层 {@code Usage} 接口只暴露 {@code promptTokens} / {@code completionTokens}，
 * 没有 cache / reasoning 维度；但 {@link Usage#getNativeUsage()} 会返回 provider 的原生
 * usage 对象。各 provider 的字段位置：</p>
 * <ul>
 *   <li><b>Anthropic</b>（{@code AnthropicApi.Usage}）：顶层 {@code cacheReadInputTokens} /
 *       {@code cacheCreationInputTokens}。注意其 {@code inputTokens} <b>不含</b>缓存部分
 *       （加法口径）。无 reasoning 计数。</li>
 *   <li><b>OpenAI 兼容</b>（{@code OpenAiApi.Usage}）：嵌套 {@code promptTokensDetails.cachedTokens}
 *       与 {@code completionTokenDetails.reasoningTokens}；{@code promptTokens} <b>已含</b>
 *       缓存命中部分（包含口径）。无 cache 写入计数。</li>
 *   <li><b>DashScope</b>（{@code DashScopeApi.TokenUsage}）：嵌套
 *       {@code promptTokenDetailed.cachedTokens}；包含口径，无写入/reasoning 计数。</li>
 * </ul>
 *
 * <p>采用反射调用以避免：
 * <ul>
 *   <li>对 spring-ai 内部 record 形态的硬编码（未来字段重命名风险小）</li>
 *   <li>对其它 provider 原生类型的编译期依赖与 ClassCastException</li>
 * </ul>
 * 反射结果按类缓存，热路径性能可接受。</p>
 *
 * <p>不可变、线程安全。</p>
 */
public final class CacheUsageExtractor {

    /** {@code (cacheReadTokens, cacheWriteTokens, reasoningTokens)}；任一字段不可得时为 0。 */
    public record CacheTokens(int cacheReadTokens, int cacheWriteTokens, int reasoningTokens) {
        public static final CacheTokens EMPTY = new CacheTokens(0, 0, 0);

        public boolean isEmpty() {
            return cacheReadTokens == 0 && cacheWriteTokens == 0 && reasoningTokens == 0;
        }
    }

    /** 缓存 (Class, methodName) → reflected Method（命中失败时为标记 NULL_METHOD）。 */
    private static final ConcurrentMap<String, Method> METHOD_CACHE = new ConcurrentHashMap<>();
    private static final Method NULL_METHOD;
    static {
        try {
            NULL_METHOD = Object.class.getMethod("toString");
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private CacheUsageExtractor() {}

    /** 从 spring-ai Usage 中尽力抽取 cache / reasoning token；不支持的 provider 返回 EMPTY。 */
    public static CacheTokens extract(Usage usage) {
        if (usage == null) return CacheTokens.EMPTY;
        Object native_ = usage.getNativeUsage();
        if (native_ == null) return CacheTokens.EMPTY;

        // Anthropic: top-level accessors on AnthropicApi.Usage
        int read  = invokeIntAccessor(native_, "cacheReadInputTokens");
        int write = invokeIntAccessor(native_, "cacheCreationInputTokens");

        // OpenAI-compatible: promptTokensDetails.cachedTokens
        if (read == 0) {
            read = invokeNestedIntAccessor(native_, "promptTokensDetails", "cachedTokens");
        }
        // DashScope: promptTokenDetailed.cachedTokens
        if (read == 0) {
            read = invokeNestedIntAccessor(native_, "promptTokenDetailed", "cachedTokens");
        }

        // OpenAI-compatible: completionTokenDetails.reasoningTokens
        int reasoning = invokeNestedIntAccessor(native_, "completionTokenDetails", "reasoningTokens");
        if (reasoning == 0) {
            // Some OpenAI-compatible gateways pluralize the field name.
            reasoning = invokeNestedIntAccessor(native_, "completionTokensDetails", "reasoningTokens");
        }

        return (read == 0 && write == 0 && reasoning == 0)
                ? CacheTokens.EMPTY
                : new CacheTokens(read, write, reasoning);
    }

    /** 两级访问：先取嵌套 detail 对象，再取其 int 字段；任一级缺失返回 0。 */
    private static int invokeNestedIntAccessor(Object target, String detailAccessor, String intAccessor) {
        Object detail = invokeAccessor(target, detailAccessor);
        if (detail == null) return 0;
        return invokeIntAccessor(detail, intAccessor);
    }

    private static int invokeIntAccessor(Object target, String accessor) {
        Object v = invokeAccessor(target, accessor);
        return v instanceof Number n ? n.intValue() : 0;
    }

    private static Object invokeAccessor(Object target, String accessor) {
        Class<?> cls = target.getClass();
        String key = cls.getName() + "#" + accessor;
        Method m = METHOD_CACHE.computeIfAbsent(key, k -> resolveAccessor(cls, accessor));
        if (m == NULL_METHOD) return null;
        try {
            return m.invoke(target);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static Method resolveAccessor(Class<?> cls, String accessor) {
        // 1) record-style getter: cacheReadInputTokens()
        try {
            Method m = cls.getMethod(accessor);
            m.setAccessible(true);
            return m;
        } catch (NoSuchMethodException ignored) {
            // 2) bean-style getter: getCacheReadInputTokens()
            String beanName = "get" + Character.toUpperCase(accessor.charAt(0)) + accessor.substring(1);
            try {
                Method m = cls.getMethod(beanName);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored2) {
                return NULL_METHOD;
            }
        }
    }
}
