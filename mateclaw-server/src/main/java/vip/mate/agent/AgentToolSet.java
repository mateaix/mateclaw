package vip.mate.agent;

import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Agent 统一工具集合
 * <p>
 * 将 @Tool Bean、ToolCallbackProvider、MCP server 暴露的 tool callbacks
 * 统一收集为一致的 ToolCallback 列表，供 StateGraph 节点使用。
 *
 * <h3>Alias index — why one tool has multiple names</h3>
 * Each tool can be referenced by several equivalent identifiers:
 * <ul>
 *   <li>{@code @Tool} function name (the runtime truth: {@code cb.getToolDefinition().name()},
 *       e.g. {@code browser_use})</li>
 *   <li>Spring bean name (e.g. {@code browserUseTool})</li>
 *   <li>Java class simple name (e.g. {@code BrowserUseTool} — what the seed data and
 *       legacy {@code mate_agent_tool.tool_name} bindings happen to store)</li>
 * </ul>
 * Filtering operations ({@link #withAllowedToolsOnly}, {@link #withDeniedToolsFiltered},
 * {@link #excluding}) accept any of these aliases, so callers don't need to know which
 * naming convention the persistence layer happens to use. This is the same pattern Spring's
 * {@code BeanFactory} uses for bean names + aliases.
 *
 * @author MateClaw Team
 */
public class AgentToolSet {

    private final List<Object> toolBeans;
    private final List<ToolCallback> callbacks;
    private final Map<String, ToolCallback> callbackByName;
    /**
     * Alias → callbacks. One alias may resolve to multiple callbacks
     * (e.g. a Spring bean name pointing at a class that exposes several {@code @Tool} methods),
     * which is why values are sets.
     */
    private final Map<String, Set<ToolCallback>> aliasIndex;

    private AgentToolSet(List<Object> toolBeans, List<ToolCallback> callbacks,
                         Function<Object, String> beanNameResolver) {
        this.toolBeans = List.copyOf(toolBeans);
        // 按工具名去重：内置工具在前（先添加），MCP 工具在后，同名时保留内置工具
        // 使用 LinkedHashMap 保证插入顺序，确保内置工具始终排在 MCP 工具前面（影响 LLM 工具选择倾向）
        LinkedHashMap<String, ToolCallback> byName = callbacks.stream()
                .collect(Collectors.toMap(
                        cb -> cb.getToolDefinition().name(),
                        cb -> cb,
                        (a, b) -> a,
                        LinkedHashMap::new));
        this.callbackByName = byName;
        // callbacks 列表也使用去重后的结果，避免 Spring AI ToolCallingChatOptions 校验重名报错
        this.callbacks = List.copyOf(byName.values());
        this.aliasIndex = buildAliasIndex(this.toolBeans, byName, beanNameResolver);
    }

    /**
     * Internal constructor for {@link #rebuild} — preserves a pre-filtered alias index
     * so we don't need {@code beanNameResolver} on every {@code with*} call.
     */
    private AgentToolSet(List<Object> toolBeans, List<ToolCallback> callbacks,
                         Map<String, Set<ToolCallback>> precomputedAliasIndex) {
        this.toolBeans = List.copyOf(toolBeans);
        LinkedHashMap<String, ToolCallback> byName = callbacks.stream()
                .collect(Collectors.toMap(
                        cb -> cb.getToolDefinition().name(),
                        cb -> cb,
                        (a, b) -> a,
                        LinkedHashMap::new));
        this.callbackByName = byName;
        this.callbacks = List.copyOf(byName.values());
        this.aliasIndex = Map.copyOf(precomputedAliasIndex);
    }

    /** No-op resolver for callers that don't have access to Spring bean names. */
    private static final Function<Object, String> NO_BEAN_NAMES = bean -> null;

    /**
     * 从预构建的 ToolCallback 列表构建工具集（用于 i18n 等需要包装 callback 的场景）
     */
    public static AgentToolSet fromCallbacks(List<Object> toolBeans, List<ToolCallback> callbacks) {
        return new AgentToolSet(toolBeans != null ? toolBeans : List.of(), callbacks, NO_BEAN_NAMES);
    }

    /**
     * Same as {@link #fromCallbacks(List, List)} but additionally indexes each tool bean by
     * its Spring bean name and Java simple class name, so {@link #withAllowedToolsOnly} accepts
     * any of those identifiers (in addition to the {@code @Tool} function name).
     *
     * @param beanNameResolver lookup from a tool bean instance to its Spring bean name;
     *                         may return {@code null} if the bean has no registered name
     */
    public static AgentToolSet fromCallbacks(List<Object> toolBeans, List<ToolCallback> callbacks,
                                             Function<Object, String> beanNameResolver) {
        return new AgentToolSet(toolBeans != null ? toolBeans : List.of(), callbacks, beanNameResolver);
    }

    /**
     * 从 @Tool Bean 列表和 ToolCallbackProvider 列表构建统一工具集
     */
    public static AgentToolSet from(List<Object> toolBeans, List<ToolCallbackProvider> providers) {
        List<ToolCallback> allCallbacks = new ArrayList<>();

        // 收集 @Tool Bean 的 callbacks
        if (toolBeans != null) {
            for (Object bean : toolBeans) {
                ToolCallback[] cbs = ToolCallbacks.from(bean);
                Collections.addAll(allCallbacks, cbs);
            }
        }

        // 收集 ToolCallbackProvider 的 callbacks
        if (providers != null) {
            for (ToolCallbackProvider provider : providers) {
                ToolCallback[] cbs = provider.getToolCallbacks();
                if (cbs != null) {
                    Collections.addAll(allCallbacks, cbs);
                }
            }
        }

        return new AgentToolSet(toolBeans != null ? toolBeans : List.of(), allCallbacks, NO_BEAN_NAMES);
    }

    /**
     * 过滤掉 denied 工具后返回新的 AgentToolSet。
     * denied 工具不会暴露给模型，模型完全不知道它们的存在。
     *
     * @param deniedTools denied 工具名集合（接受 function name / bean name / class simple name；
     *                    为空或 null 时直接返回 this）
     */
    public AgentToolSet withDeniedToolsFiltered(Set<String> deniedTools) {
        if (deniedTools == null || deniedTools.isEmpty()) {
            return this;
        }
        Set<ToolCallback> denied = resolveAliases(deniedTools);
        if (denied.isEmpty()) {
            return this;
        }
        List<ToolCallback> filtered = callbacks.stream()
                .filter(cb -> !denied.contains(cb))
                .toList();
        return rebuild(filtered);
    }

    /**
     * 仅保留指定名称的工具（白名单模式，用于 per-agent 绑定）
     *
     * @param allowedTools 允许的工具名集合（接受 function name / Spring bean name / Java class simple name；
     *                     为 null 时直接返回 this，表示使用全局默认）
     */
    public AgentToolSet withAllowedToolsOnly(Set<String> allowedTools) {
        if (allowedTools == null) {
            return this; // null = 无绑定，使用全局默认
        }
        Set<ToolCallback> allowed = resolveAliases(allowedTools);
        List<ToolCallback> filtered = callbacks.stream()
                .filter(allowed::contains)
                .toList();
        return rebuild(filtered);
    }

    /**
     * 获取所有 ToolCallback
     */
    public List<ToolCallback> callbacks() {
        return callbacks;
    }

    /**
     * 获取按名称索引的 ToolCallback Map
     */
    public Map<String, ToolCallback> callbackByName() {
        return callbackByName;
    }

    /**
     * 获取原始的 @Tool Bean 列表
     */
    public List<Object> toolBeans() {
        return toolBeans;
    }

    /**
     * 返回排除指定工具名后的新 AgentToolSet
     *
     * @param toolNames 要排除的工具名集合（接受 function name / bean name / class simple name）
     */
    public AgentToolSet excluding(Set<String> toolNames) {
        if (toolNames == null || toolNames.isEmpty()) {
            return this;
        }
        Set<ToolCallback> excluded = resolveAliases(toolNames);
        if (excluded.isEmpty()) {
            return this;
        }
        List<ToolCallback> filtered = callbacks.stream()
                .filter(cb -> !excluded.contains(cb))
                .toList();
        return rebuild(filtered);
    }

    /**
     * 是否为空（无任何工具）
     */
    public boolean isEmpty() {
        return callbacks.isEmpty();
    }

    /**
     * 工具数量
     */
    public int size() {
        return callbacks.size();
    }

    /**
     * Resolve a mix of aliases (function name / Spring bean name / Java class simple name)
     * to the {@code @Tool} function names they map to. Used to bridge persistence layers
     * that key a tool by its class or bean name (e.g. {@code mate_tool.name}) onto the
     * runtime callback name ({@code cb.getToolDefinition().name()}). Unknown aliases yield
     * nothing.
     */
    public Set<String> functionNamesFor(Set<String> aliases) {
        if (aliases == null || aliases.isEmpty()) {
            return Set.of();
        }
        return resolveAliases(aliases).stream()
                .map(cb -> cb.getToolDefinition().name())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    // ==================== Internals ====================

    /**
     * Resolve a set of aliases (any mix of function name / bean name / class simple name)
     * into the set of {@link ToolCallback} instances they refer to. Unknown aliases are
     * silently dropped — the caller is expected to be tolerant of stale persistence data.
     */
    private Set<ToolCallback> resolveAliases(Set<String> aliases) {
        Set<ToolCallback> resolved = new LinkedHashSet<>();
        for (String alias : aliases) {
            Set<ToolCallback> hits = aliasIndex.get(alias);
            if (hits != null) {
                resolved.addAll(hits);
            }
        }
        return resolved;
    }

    /**
     * Reconstruct a new {@code AgentToolSet} after filtering callbacks, carrying forward
     * only the alias entries whose targets survived. This avoids re-running
     * {@link ToolCallbacks#from(Object)} reflection on every {@code with*} call.
     */
    private AgentToolSet rebuild(List<ToolCallback> filteredCallbacks) {
        Set<ToolCallback> survivors = new HashSet<>(filteredCallbacks);
        Map<String, Set<ToolCallback>> filteredAliases = new LinkedHashMap<>();
        for (Map.Entry<String, Set<ToolCallback>> e : aliasIndex.entrySet()) {
            Set<ToolCallback> kept = new LinkedHashSet<>();
            for (ToolCallback cb : e.getValue()) {
                if (survivors.contains(cb)) {
                    kept.add(cb);
                }
            }
            if (!kept.isEmpty()) {
                filteredAliases.put(e.getKey(), Set.copyOf(kept));
            }
        }
        return new AgentToolSet(toolBeans, filteredCallbacks, filteredAliases);
    }

    /**
     * Build the alias index. Function names are always indexed (they are the runtime truth);
     * bean names and class simple names are indexed when {@code beanNameResolver} is provided
     * — typically only the production registry has the {@link org.springframework.context.ApplicationContext}
     * needed to map bean instances to names. Unit tests that pass empty {@code toolBeans}
     * naturally get a function-name-only index.
     */
    private static Map<String, Set<ToolCallback>> buildAliasIndex(
            List<Object> toolBeans,
            Map<String, ToolCallback> callbackByName,
            Function<Object, String> beanNameResolver) {

        Map<String, Set<ToolCallback>> aliases = new LinkedHashMap<>();

        // 1. Always index by function name (the runtime identifier)
        for (Map.Entry<String, ToolCallback> e : callbackByName.entrySet()) {
            aliases.computeIfAbsent(e.getKey(), k -> new LinkedHashSet<>()).add(e.getValue());
        }

        // 2. If we have bean info, also index by Spring bean name and Java class simple name.
        //    A single bean may expose multiple @Tool methods → the alias maps to a set.
        if (beanNameResolver != null) {
            for (Object bean : toolBeans) {
                String beanName = beanNameResolver.apply(bean);
                String simpleName = bean.getClass().getSimpleName();

                // Find which callbacks belong to this bean, looking them up in the
                // (possibly i18n-wrapped) callbackByName so we point at the same
                // instances the rest of the set uses.
                Set<ToolCallback> beanCallbacks = new LinkedHashSet<>();
                ToolCallback[] rawCallbacks;
                try {
                    rawCallbacks = ToolCallbacks.from(bean);
                } catch (Exception ignored) {
                    // Defensive: a misbehaving bean shouldn't break the whole tool set
                    continue;
                }
                for (ToolCallback raw : rawCallbacks) {
                    ToolCallback wrapped = callbackByName.get(raw.getToolDefinition().name());
                    if (wrapped != null) {
                        beanCallbacks.add(wrapped);
                    }
                }
                if (beanCallbacks.isEmpty()) {
                    continue;
                }
                if (beanName != null && !beanName.isBlank()) {
                    aliases.computeIfAbsent(beanName, k -> new LinkedHashSet<>()).addAll(beanCallbacks);
                }
                if (simpleName != null && !simpleName.isBlank()) {
                    aliases.computeIfAbsent(simpleName, k -> new LinkedHashSet<>()).addAll(beanCallbacks);
                }
            }
        }

        // Freeze inner sets
        Map<String, Set<ToolCallback>> frozen = new LinkedHashMap<>();
        for (Map.Entry<String, Set<ToolCallback>> e : aliases.entrySet()) {
            frozen.put(e.getKey(), Set.copyOf(e.getValue()));
        }
        return Map.copyOf(frozen);
    }
}
