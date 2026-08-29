package vip.mate.tool;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import vip.mate.tool.model.ToolEntity;
import vip.mate.tool.repository.ToolMapper;

import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import vip.mate.agent.AgentToolSet;
import vip.mate.i18n.I18nService;
import vip.mate.i18n.LocaleAwareToolCallback;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 工具注册中心
 * 管理所有可供 Agent 使用的工具（内置 + 自定义）
 * 工具启用状态由数据库 mate_tool 表的 enabled 字段控制
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolRegistry {

    private final ApplicationContext applicationContext;
    private final ToolMapper toolMapper;
    private final I18nService i18nService;

    // ==================== Plugin Tools ====================

    /** Plugin-registered tool entries with lazy availability checks */
    private final CopyOnWriteArrayList<PluginToolEntry> pluginTools = new CopyOnWriteArrayList<>();

    private final Object enabledToolSetLock = new Object();
    private volatile AgentToolSet enabledToolSetCache;

    /** A tool entry registered by a plugin */
    public record PluginToolEntry(ToolCallback callback, Supplier<Boolean> availabilityCheck) {}

    /**
     * Register a tool from a plugin with an availability check.
     * The check is evaluated lazily each time the tool set is built.
     */
    public void registerPluginTool(ToolCallback callback, Supplier<Boolean> availabilityCheck) {
        pluginTools.add(new PluginToolEntry(callback, availabilityCheck != null ? availabilityCheck : () -> true));
        invalidateEnabledToolSetCache("plugin-tool-registered:" + callback.getToolDefinition().name());
        log.info("Plugin tool registered: {}", callback.getToolDefinition().name());
    }

    /**
     * Unregister a plugin tool by name.
     */
    public void unregisterPluginTool(String toolName) {
        pluginTools.removeIf(entry -> entry.callback().getToolDefinition().name().equals(toolName));
        invalidateEnabledToolSetCache("plugin-tool-unregistered:" + toolName);
        log.info("Plugin tool unregistered: {}", toolName);
    }

    /**
     * Snapshot plugin callbacks that are currently available to the runtime.
     * Used by the agent tool picker so plugin tools can be bound per agent
     * through the same {@code mate_agent_tool.tool_name} path as other tools.
     */
    public List<ToolCallback> listAvailablePluginTools() {
        List<ToolCallback> out = new ArrayList<>();
        for (PluginToolEntry entry : pluginTools) {
            try {
                if (entry.callback() != null && Boolean.TRUE.equals(entry.availabilityCheck().get())) {
                    out.add(entry.callback());
                }
            } catch (Exception e) {
                String name = entry.callback() != null && entry.callback().getToolDefinition() != null
                        ? entry.callback().getToolDefinition().name()
                        : "<unknown>";
                log.warn("Plugin tool availability check failed for {}: {}", name, e.getMessage());
            }
        }
        return List.copyOf(out);
    }

    public void invalidateEnabledToolSetCache(String reason) {
        enabledToolSetCache = null;
        log.debug("Enabled AgentToolSet cache invalidated: {}", reason);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void prewarmEnabledToolSetCache() {
        CompletableFuture.runAsync(() -> {
            try {
                getEnabledToolSet();
            } catch (Exception e) {
                log.debug("Enabled AgentToolSet prewarm skipped: {}", e.getMessage());
            }
        });
    }

    @EventListener
    public void onMcpServerChanged(vip.mate.tool.mcp.event.McpServerChangedEvent event) {
        invalidateEnabledToolSetCache("mcp-server-changed:" + event.reason());
        prewarmEnabledToolSetCache();
    }

    @EventListener
    public void onMcpConnectionLost(vip.mate.tool.mcp.event.McpConnectionLostEvent event) {
        invalidateEnabledToolSetCache("mcp-connection-lost:" + event.serverId());
    }

    /**
     * 获取所有已启用的工具 Bean（Spring AI @Tool 注解方式）
     * 通过数据库 enabled 标志过滤，确保 UI 开关真正生效
     */
    public List<Object> getEnabledTools() {
        return List.copyOf(getEnabledToolBeansByName().values());
    }

    /**
     * Iterate Spring beans once, returning a {@code beanName → bean} map of every
     * currently-enabled @Tool bean.
     * <p>
     * This is the single source of truth for "which @Tool beans should the agent see"; both
     * {@link #getEnabledTools()} and {@link #getEnabledToolSet()} build on it. Returning
     * {@link LinkedHashMap} preserves the discovery order from {@code getBeansWithAnnotation},
     * which {@link AgentToolSet} relies on (built-in tools first, MCP tools second).
     */
    private LinkedHashMap<String, Object> getEnabledToolBeansByName() {
        return getToolBeansByName(true);
    }

    /**
     * Iterate Spring beans once, returning a {@code beanName → bean} map of
     * every @Tool bean. When {@code enabledOnly} is true, DB rows with
     * {@code enabled=false} are excluded; when false, disabled rows are kept for
     * admin metadata use cases where the UI still needs to resolve aliases.
     */
    private LinkedHashMap<String, Object> getToolBeansByName(boolean enabledOnly) {
        // 1. 从数据库获取明确禁用的 beanName 黑名单
        //    逻辑：只有 DB 中存在记录且 enabled=false 的才跳过
        //    DB 中没有记录的 bean 默认启用（向后兼容 + 新工具自动可用）
        Set<String> disabledBeanNames = enabledOnly
                ? toolMapper.selectList(
                        new LambdaQueryWrapper<ToolEntity>()
                                .eq(ToolEntity::getEnabled, false)
                                .isNotNull(ToolEntity::getBeanName)
                ).stream()
                        .map(ToolEntity::getBeanName)
                        .collect(Collectors.toSet())
                : Set.of();

        LinkedHashMap<String, Object> enabled = new LinkedHashMap<>();

        // 2. 扫描 Spring 容器中所有带 @Tool 方法的 Bean
        Map<String, Object> beans = applicationContext.getBeansWithAnnotation(Component.class);
        for (Map.Entry<String, Object> entry : beans.entrySet()) {
            String beanName = entry.getKey();
            Object bean = entry.getValue();

            boolean hasToolMethod = java.util.Arrays.stream(bean.getClass().getMethods())
                    .anyMatch(m -> m.isAnnotationPresent(Tool.class));

            if (!hasToolMethod) {
                continue;
            }
            // 3. 只有 DB 中明确 enabled=false 的才跳过，其余全部启用
            if (disabledBeanNames.contains(beanName)) {
                log.debug("Skipped disabled tool bean: {} (beanName={})", bean.getClass().getSimpleName(), beanName);
            } else {
                enabled.put(beanName, bean);
                log.debug("Registered tool bean: {} (beanName={})", bean.getClass().getSimpleName(), beanName);
            }
        }

        log.info("Total {} tools: {}", enabledOnly ? "enabled" : "registered", enabled.size());
        return enabled;
    }

    /**
     * Build an alias index for every registered {@code @Tool} bean, including
     * rows disabled in DB. This is for admin display only; runtime tool
     * exposure must continue to use {@link #getEnabledToolSet()}.
     */
    public AgentToolSet getAllToolBeanSetForAdmin() {
        LinkedHashMap<String, Object> beansByName = getToolBeansByName(false);
        List<Object> toolBeans = new ArrayList<>(beansByName.values());
        IdentityHashMap<Object, String> nameByBean = new IdentityHashMap<>();
        for (Map.Entry<String, Object> e : beansByName.entrySet()) {
            nameByBean.put(e.getValue(), e.getKey());
        }
        List<ToolCallback> callbacks = new ArrayList<>();
        for (Object bean : toolBeans) {
            Collections.addAll(callbacks, ToolCallbacks.from(bean));
        }
        return AgentToolSet.fromCallbacks(toolBeans, callbacks, nameByBean::get);
    }

    /**
     * Resolve aliases for currently enabled built-in {@code @Tool} beans without
     * touching {@link ToolCallbackProvider}s. This is intentionally narrower than
     * {@link #getEnabledToolSet()}: disclosure-tier snapshots only need to bridge
     * {@code mate_tool.name}/{@code bean_name} onto built-in function names, and
     * calling providers here would synchronously enumerate MCP tools on the chat
     * hot path.
     */
    public Set<String> enabledToolBeanFunctionNamesFor(Set<String> aliases) {
        if (aliases == null || aliases.isEmpty()) {
            return Set.of();
        }
        Map<String, Set<String>> index = enabledToolBeanFunctionNameIndex();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String alias : aliases) {
            Set<String> hits = index.get(alias);
            if (hits != null) {
                out.addAll(hits);
            }
        }
        return out;
    }

    /**
     * Build {@code alias -> @Tool function names} for enabled built-in tool beans.
     * The aliases mirror {@link AgentToolSet}: function name, Spring bean name,
     * and Java simple class name. Provider/MCP callbacks are deliberately absent.
     */
    public Map<String, Set<String>> enabledToolBeanFunctionNameIndex() {
        LinkedHashMap<String, Object> beansByName = getEnabledToolBeansByName();
        Map<String, Set<String>> index = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : beansByName.entrySet()) {
            String beanName = entry.getKey();
            Object bean = entry.getValue();
            ToolCallback[] callbacks = ToolCallbacks.from(bean);
            LinkedHashSet<String> functionNames = new LinkedHashSet<>();
            for (ToolCallback cb : callbacks) {
                if (cb != null && cb.getToolDefinition() != null) {
                    functionNames.add(cb.getToolDefinition().name());
                }
            }
            if (functionNames.isEmpty()) {
                continue;
            }
            putAlias(index, beanName, functionNames);
            putAlias(index, bean.getClass().getSimpleName(), functionNames);
            for (String functionName : functionNames) {
                putAlias(index, functionName, Set.of(functionName));
            }
        }
        return index;
    }

    private static void putAlias(Map<String, Set<String>> index, String alias, Set<String> functionNames) {
        if (alias == null || alias.isBlank() || functionNames == null || functionNames.isEmpty()) {
            return;
        }
        index.computeIfAbsent(alias, ignored -> new LinkedHashSet<>()).addAll(functionNames);
    }

    /**
     * 获取统一的 AgentToolSet（包含 @Tool Bean + ToolCallbackProvider）
     * <p>
     * 同时收集：
     * 1. 当前启用的 @Tool bean
     * 2. 当前容器中所有 ToolCallbackProvider（MCP server 等）
     */
    public AgentToolSet getEnabledToolSet() {
        AgentToolSet cached = enabledToolSetCache;
        if (cached != null) {
            return cached;
        }
        synchronized (enabledToolSetLock) {
            cached = enabledToolSetCache;
            if (cached != null) {
                return cached;
            }
            AgentToolSet built = buildEnabledToolSet();
            enabledToolSetCache = built;
            return built;
        }
    }

    private AgentToolSet buildEnabledToolSet() {
        // Build both the bean list and the identity-based name lookup in one pass — the
        // latter lets AgentToolSet's alias index resolve a saved binding like
        // "BrowserUseTool" or "browserUseTool" back to the same callback as "browser_use".
        LinkedHashMap<String, Object> beansByName = getEnabledToolBeansByName();
        List<Object> toolBeans = new ArrayList<>(beansByName.values());
        IdentityHashMap<Object, String> nameByBean = new IdentityHashMap<>();
        for (Map.Entry<String, Object> e : beansByName.entrySet()) {
            nameByBean.put(e.getValue(), e.getKey());
        }

        Map<String, ToolCallbackProvider> providerBeans = applicationContext.getBeansOfType(ToolCallbackProvider.class);
        List<ToolCallbackProvider> providers = new ArrayList<>(providerBeans.values());

        // 对内置工具 callback 应用 i18n 描述包装
        List<ToolCallback> localizedCallbacks = new ArrayList<>();
        for (Object bean : toolBeans) {
            ToolCallback[] cbs = ToolCallbacks.from(bean);
            for (ToolCallback cb : cbs) {
                String toolName = cb.getToolDefinition().name();
                String descKey = "tool." + toolName + ".desc";
                // The i18n description is an optional override: tools without a
                // bundle entry (e.g. wiki tools) keep the description declared on
                // their @Tool annotation. Use msgOptional so an absent key is not
                // logged as a "missing key" — that is expected, not a fault.
                String localizedDesc = i18nService.msgOptional(descKey);
                if (localizedDesc != null) {
                    localizedCallbacks.add(new LocaleAwareToolCallback(cb, localizedDesc));
                } else {
                    localizedCallbacks.add(cb);
                }
            }
        }

        // MCP provider callbacks 不做 i18n 包装（MCP 工具自行管理描述）
        for (ToolCallbackProvider provider : providers) {
            ToolCallback[] cbs = provider.getToolCallbacks();
            if (cbs != null) {
                Collections.addAll(localizedCallbacks, cbs);
            }
        }

        // Plugin tool callbacks — evaluate availability checks lazily
        int pluginToolCount = 0;
        for (PluginToolEntry entry : pluginTools) {
            try {
                if (Boolean.TRUE.equals(entry.availabilityCheck().get())) {
                    localizedCallbacks.add(entry.callback());
                    pluginToolCount++;
                } else {
                    log.debug("Plugin tool excluded (availability check failed): {}",
                            entry.callback().getToolDefinition().name());
                }
            } catch (Exception e) {
                log.warn("Plugin tool availability check failed for {}: {}",
                        entry.callback().getToolDefinition().name(), e.getMessage());
            }
        }

        log.info("Building AgentToolSet: toolBeans={}, providers={}, pluginTools={}, totalCallbacks={}",
                toolBeans.size(), providers.size(), pluginToolCount, localizedCallbacks.size());
        return AgentToolSet.fromCallbacks(toolBeans, localizedCallbacks, nameByBean::get);
    }

    /**
     * Returns every runtime identifier by which a currently-enabled tool can be
     * referenced — SKILL.md authors use all three conventions interchangeably:
     * <ul>
     *   <li>{@code @Tool} function name (e.g. {@code browser_use}, {@code runSkillScript})</li>
     *   <li>Spring bean name (e.g. {@code browserUseTool}, {@code skillScriptTool})</li>
     *   <li>MCP tool id / plugin tool name (routed via {@code ToolCallbackProvider})</li>
     * </ul>
     * Returning the union lets {@link vip.mate.skill.runtime.SkillDependencyChecker}
     * accept whichever convention a skill happens to declare.
     */
    public Set<String> availableFunctionNames() {
        return getEnabledToolSet().allNames();
    }

    /**
     * 获取数据库中的工具配置列表（全部）
     */
    public List<ToolEntity> listToolEntities() {
        return toolMapper.selectList(new LambdaQueryWrapper<ToolEntity>()
                .orderByDesc(ToolEntity::getBuiltin)
                .orderByAsc(ToolEntity::getName));
    }

    /**
     * 获取已启用的工具配置列表
     */
    public List<ToolEntity> listEnabledToolEntities() {
        return toolMapper.selectList(new LambdaQueryWrapper<ToolEntity>()
                .eq(ToolEntity::getEnabled, true)
                .orderByAsc(ToolEntity::getName));
    }
}
