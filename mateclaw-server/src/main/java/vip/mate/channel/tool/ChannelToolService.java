package vip.mate.channel.tool;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import vip.mate.channel.model.ChannelEntity;
import vip.mate.channel.repository.ChannelMapper;
import vip.mate.tool.ToolRegistry;
import vip.mate.tool.guard.engine.ToolGuardRuleRegistry;
import vip.mate.tool.guard.model.ToolGuardRuleEntity;
import vip.mate.tool.guard.repository.ToolGuardRuleMapper;
import vip.mate.tool.model.ToolEntity;
import vip.mate.tool.repository.ToolMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Node-local reconciler that keeps every {@link ChannelToolProvider}'s
 * tool callbacks registered in {@link ToolRegistry} in sync with the
 * currently-enabled {@code mate_channel} rows whose type has a
 * registered provider.
 *
 * <p>Three trigger points, all idempotent:
 * <ol>
 *   <li><b>Startup</b> — {@link ApplicationRunner} runs once per node
 *       after the Spring context is ready</li>
 *   <li><b>Periodic (60s)</b> — picks up changes made by another node
 *       (config rotated, channel enabled / disabled), per RFC v3
 *       "reconcile-driven, not adapter-lifecycle-driven"</li>
 *   <li><b>Local CRUD</b> — {@code ChannelService} calls
 *       {@link #syncNow()} after every channel mutation so the local
 *       node aligns instantly (other nodes wait at most one tick)</li>
 * </ol>
 *
 * <p>Decoupled from {@code ChannelManager} / adapter lifecycle / leader
 * election: tools are pure OpenAPI calls keyed by channelId and never
 * need the WebSocket or the leader lease. RFC §4.1 makes this explicit.
 *
 * <p>Tool names get a stable {@code _c<channelId>} suffix
 * unconditionally — the channelId is immutable for the channel's
 * lifetime, so the registered tool name never drifts even when other
 * channels of the same type are added / deleted (RFC v5 §4.3).
 */
@Slf4j
@Component
public class ChannelToolService {

    /** Periodic reconcile cadence — RFC default. */
    static final long RECONCILE_INTERVAL_SECONDS = 60;

    /** Suffix prefix appended before the channelId on the actual tool name. */
    public static final String INSTANCE_SUFFIX_PREFIX = "_c";

    private final List<ChannelToolProvider> providerBeans;
    private final ChannelMapper channelMapper;
    private final ToolMapper toolMapper;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;
    private final ToolGuardRuleMapper guardRuleMapper;
    private final ToolGuardRuleRegistry guardRuleRegistry;

    /** Indexed at startup: channelType → provider. */
    private Map<String, ChannelToolProvider> providersByType = Map.of();

    /** Node-local registration state: channelId → list of actual tool names registered. */
    private final ConcurrentHashMap<Long, List<String>> registered = new ConcurrentHashMap<>();

    /** channelId → the {@code update_time} value last seen on reconcile (config-change detection). */
    private final ConcurrentHashMap<Long, LocalDateTime> registeredUpdateTime = new ConcurrentHashMap<>();

    /** Daemon scheduler for the periodic tick. */
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "channel-tool-reconcile");
        t.setDaemon(true);
        return t;
    });

    public ChannelToolService(List<ChannelToolProvider> providerBeans,
                               ChannelMapper channelMapper,
                               ToolMapper toolMapper,
                               ToolRegistry toolRegistry,
                               ObjectMapper objectMapper,
                               ToolGuardRuleMapper guardRuleMapper,
                               ToolGuardRuleRegistry guardRuleRegistry) {
        this.providerBeans = providerBeans != null ? providerBeans : List.of();
        this.channelMapper = channelMapper;
        this.toolMapper = toolMapper;
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
        this.guardRuleMapper = guardRuleMapper;
        this.guardRuleRegistry = guardRuleRegistry;
    }

    @PostConstruct
    void index() {
        providersByType = providerBeans.stream()
                .collect(Collectors.toUnmodifiableMap(
                        ChannelToolProvider::channelType,
                        p -> p,
                        (a, b) -> {
                            log.warn("[channel-tool] Duplicate ChannelToolProvider for type {} ({} vs {}); keeping first",
                                    a.channelType(), a.getClass().getSimpleName(), b.getClass().getSimpleName());
                            return a;
                        }));
        if (providersByType.isEmpty()) {
            log.info("[channel-tool] No ChannelToolProvider beans wired — reconcile loop will be a no-op");
        } else {
            log.info("[channel-tool] Registered providers: {}", providersByType.keySet());
        }
        // Schedule the periodic tick. Startup reconcile runs separately
        // via the ApplicationRunner bean below so the Spring context
        // is fully ready (including any provider's transitive deps).
        scheduler.scheduleAtFixedRate(this::tickQuietly,
                RECONCILE_INTERVAL_SECONDS, RECONCILE_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Initial reconcile after Spring is fully ready. Spring runs every
     * {@link ApplicationRunner} bean after all {@code @PostConstruct}
     * hooks but before serving traffic.
     */
    @org.springframework.context.annotation.Bean
    ApplicationRunner channelToolStartupReconcile() {
        return args -> {
            log.info("[channel-tool] Startup reconcile");
            tickQuietly();
        };
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdown();
    }

    /**
     * Trigger an immediate reconcile from this node. Called by
     * {@code ChannelService} after channel CRUD so the local node
     * aligns within the same request; other nodes catch up at the
     * next periodic tick.
     */
    public void syncNow() {
        tickQuietly();
    }

    private void tickQuietly() {
        try {
            reconcile();
        } catch (Exception e) {
            log.warn("[channel-tool] reconcile failed (will retry next tick): {}", e.getMessage(), e);
        }
    }

    /**
     * Diff the local registration against the expected set (enabled
     * channels of types we have providers for) and apply just the
     * deltas — unregister stale, register new, rebuild on config change.
     * Idempotent.
     */
    synchronized void reconcile() {
        if (providersByType.isEmpty()) {
            return;
        }
        List<ChannelEntity> enabled = channelMapper.selectList(
                new LambdaQueryWrapper<ChannelEntity>().eq(ChannelEntity::getEnabled, true));
        Map<Long, ChannelEntity> desired = new HashMap<>();
        for (ChannelEntity ch : enabled) {
            if (providersByType.containsKey(ch.getChannelType())) {
                desired.put(ch.getId(), ch);
            }
        }

        // 0. Cross-process orphan sweep — catches rows left over from channels
        // that were deleted while this node was down (the unregister loop below
        // only sees channels this process once registered, so without the sweep
        // pre-existing orphans live forever).
        sweepOrphans(desired.keySet());

        // 1. Unregister channels no longer in the desired set
        for (Long goneId : new ArrayList<>(registered.keySet())) {
            if (!desired.containsKey(goneId)) {
                unregisterChannel(goneId);
                deleteToolRows(goneId);
            }
        }

        // 2. Register / re-register changed or new channels
        for (ChannelEntity ch : desired.values()) {
            LocalDateTime seen = registeredUpdateTime.get(ch.getId());
            if (seen != null && seen.equals(ch.getUpdateTime())) {
                continue; // already registered + config unchanged
            }
            // Config changed — drop the stale callbacks before re-registering
            // so handler closures don't keep stale config.
            unregisterChannel(ch.getId());
            try {
                registerChannel(ch);
            } catch (Exception e) {
                log.warn("[channel-tool] register failed for channel {} ({}): {}",
                        ch.getId(), ch.getChannelType(), e.getMessage());
            }
        }
    }

    private void registerChannel(ChannelEntity ch) {
        ChannelToolProvider provider = providersByType.get(ch.getChannelType());
        if (provider == null) return;
        List<ChannelToolDescriptor> descriptors = provider.describeTools();
        if (descriptors == null || descriptors.isEmpty()) {
            log.debug("[channel-tool] Provider {} returned no descriptors; skipping channel {}",
                    provider.channelType(), ch.getId());
            return;
        }
        Map<String, String> nameMap = upsertToolRows(ch, descriptors);
        seedGuardRules(ch, descriptors, nameMap);

        ChannelToolContext context = new ChannelToolContext(
                ch.getId(), ch.getName(), ch.getChannelType(), ch.getAgentId(),
                parseConfig(ch.getConfigJson()));
        List<ToolCallback> callbacks;
        try {
            callbacks = provider.createTools(context);
        } catch (Exception e) {
            log.warn("[channel-tool] createTools failed for channel {} ({}): {}",
                    ch.getId(), provider.channelType(), e.getMessage());
            return;
        }
        if (callbacks == null) return;

        List<String> actualNames = new ArrayList<>();
        for (ToolCallback cb : callbacks) {
            String baseName = cb.getToolDefinition().name();
            String actualName = nameMap.getOrDefault(baseName, baseName + INSTANCE_SUFFIX_PREFIX + ch.getId());
            ToolCallback renamed = (cb instanceof ChannelToolCallback ctc)
                    ? ctc.renamed(actualName)
                    : cb;  // legacy callback (any other ToolCallback impl) registers under its own name
            toolRegistry.registerPluginTool(renamed, () -> isToolRowEnabled(actualName));
            actualNames.add(actualName);
        }
        registered.put(ch.getId(), actualNames);
        registeredUpdateTime.put(ch.getId(), ch.getUpdateTime());
        log.info("[channel-tool] Registered {} tool(s) for channel {} ({})",
                actualNames.size(), ch.getId(), ch.getChannelType());
    }

    private void unregisterChannel(Long channelId) {
        registeredUpdateTime.remove(channelId);
        List<String> names = registered.remove(channelId);
        if (names != null && !names.isEmpty()) {
            names.forEach(toolRegistry::unregisterPluginTool);
            log.info("[channel-tool] Unregistered {} tool(s) for channel {}", names.size(), channelId);
        }
    }

    /**
     * Upsert {@code mate_tool} rows for each descriptor; returns the
     * baseName → actual-name map the caller uses when renaming the
     * provider's callbacks. DB-level uniqueness on {@code mate_tool.name}
     * (introduced in V100) makes this safe under concurrent reconcile
     * from multiple nodes.
     */
    private Map<String, String> upsertToolRows(ChannelEntity ch, List<ChannelToolDescriptor> descriptors) {
        Map<String, String> nameMap = new HashMap<>();
        for (ChannelToolDescriptor d : descriptors) {
            String actualName = d.name() + INSTANCE_SUFFIX_PREFIX + ch.getId();
            nameMap.put(d.name(), actualName);

            ToolEntity existing = toolMapper.selectOne(
                    new LambdaQueryWrapper<ToolEntity>().eq(ToolEntity::getName, actualName));
            if (existing == null) {
                ToolEntity row = new ToolEntity();
                row.setName(actualName);
                row.setDisplayName(d.displayName() + " (" + ch.getName() + ")");
                row.setDescription(d.description());
                row.setToolType("channel");
                row.setParamsSchema(d.inputSchema());
                row.setEnabled(d.enabledByDefault());
                row.setBuiltin(false);
                row.setChannelId(ch.getId());
                try {
                    toolMapper.insert(row);
                } catch (org.springframework.dao.DuplicateKeyException race) {
                    // Another node beat us to the insert — that's the
                    // whole point of the uk_mate_tool_name unique index.
                    log.debug("[channel-tool] tool row {} already inserted by another node", actualName);
                }
            } else {
                // Refresh metadata that may have evolved between releases
                // (description rewrites, schema updates) without clobbering
                // the user's enable / disable preference.
                boolean dirty = false;
                String newDisplay = d.displayName() + " (" + ch.getName() + ")";
                if (!newDisplay.equals(existing.getDisplayName())) { existing.setDisplayName(newDisplay); dirty = true; }
                if (!d.description().equals(existing.getDescription())) { existing.setDescription(d.description()); dirty = true; }
                if (!d.inputSchema().equals(existing.getParamsSchema())) { existing.setParamsSchema(d.inputSchema()); dirty = true; }
                if (existing.getChannelId() == null) { existing.setChannelId(ch.getId()); dirty = true; }
                if (!"channel".equals(existing.getToolType())) { existing.setToolType("channel"); dirty = true; }
                if (dirty) toolMapper.updateById(existing);
            }
        }
        return nameMap;
    }

    /**
     * Seed one HIGH-severity DB rule per mutating descriptor so the
     * tool's invocation gets evaluated by {@code DbRuleGuardian} →
     * {@code NEEDS_APPROVAL}. The rule pattern is {@code ".*"} so
     * every invocation matches; the severity is what drives the
     * approval decision, not pattern specificity.
     *
     * <p>Idempotent: a stable {@code rule_id} per (tool, channel) +
     * {@code ON DUPLICATE KEY UPDATE}-style upsert keeps re-reconcile
     * safe. Triggers a registry reload so the new rule is immediately
     * visible to the next invocation.
     */
    private void seedGuardRules(ChannelEntity ch, List<ChannelToolDescriptor> descriptors, Map<String, String> nameMap) {
        String legacyName = "Channel write tool — approval required";
        String channelScopedName = legacyName + " (" + ch.getName() + ")";
        boolean changed = false;
        for (ChannelToolDescriptor d : descriptors) {
            if (!d.mutating()) continue;
            String actualName = nameMap.get(d.name());
            if (actualName == null) continue;
            String ruleId = "channel_tool:" + actualName;
            ToolGuardRuleEntity existing = guardRuleMapper.selectOne(
                    new LambdaQueryWrapper<ToolGuardRuleEntity>().eq(ToolGuardRuleEntity::getRuleId, ruleId));
            if (existing != null) {
                // One-time migration: rename rows that still carry the original
                // hardcoded label so the UI can tell channels apart. User-edited
                // names (anything other than the legacy literal) are preserved.
                if (legacyName.equals(existing.getName())) {
                    existing.setName(channelScopedName);
                    guardRuleMapper.updateById(existing);
                    changed = true;
                }
                continue;
            }
            ToolGuardRuleEntity row = new ToolGuardRuleEntity();
            row.setRuleId(ruleId);
            row.setName(channelScopedName);
            row.setDescription("Auto-seeded approval gate for channel-native write tool " + actualName);
            row.setToolName(actualName);
            row.setParamName("args");
            row.setCategory("SENSITIVE_FILE_ACCESS");
            row.setSeverity("HIGH");
            row.setDecision("NEEDS_APPROVAL");
            row.setPattern(".*");          // every invocation matches
            row.setRemediation("Confirm the requested change is intended, then approve.");
            row.setBuiltin(false);
            row.setEnabled(true);
            row.setPriority(100);
            try {
                guardRuleMapper.insert(row);
                changed = true;
                log.info("[channel-tool] Seeded approval rule for write tool {}", actualName);
            } catch (org.springframework.dao.DuplicateKeyException race) {
                // Another node beat us to it — the existing row is fine.
            } catch (Exception e) {
                log.warn("[channel-tool] Failed to seed guard rule for {}: {}", actualName, e.getMessage());
            }
        }
        if (changed) {
            try {
                guardRuleRegistry.reload();
            } catch (Exception e) {
                log.debug("[channel-tool] guard rule reload failed (non-fatal): {}", e.getMessage());
            }
        }
    }

    /**
     * Reverse reconciliation: drop any channel-scoped {@code mate_tool} row
     * whose {@code channel_id} is not in the live set, then drop any seeded
     * {@code mate_tool_guard_rule} whose target tool no longer exists. Closes
     * the gap left by {@link #reconcile()}'s unregister loop, which only sees
     * channels this process registered itself — orphans from channels deleted
     * while the node was down survived previously.
     */
    private void sweepOrphans(Set<Long> liveChannelIds) {
        List<ToolEntity> channelTools = toolMapper.selectList(
                new LambdaQueryWrapper<ToolEntity>().eq(ToolEntity::getToolType, "channel"));
        List<Long> staleToolIds = channelTools.stream()
                .filter(t -> t.getChannelId() == null || !liveChannelIds.contains(t.getChannelId()))
                .map(ToolEntity::getId)
                .toList();
        if (!staleToolIds.isEmpty()) {
            toolMapper.delete(new LambdaQueryWrapper<ToolEntity>().in(ToolEntity::getId, staleToolIds));
            log.info("[channel-tool] Swept {} orphan mate_tool row(s)", staleToolIds.size());
        }

        Set<String> liveChannelToolNames = new HashSet<>();
        for (ToolEntity t : channelTools) {
            if (t.getChannelId() != null && liveChannelIds.contains(t.getChannelId())) {
                liveChannelToolNames.add(t.getName());
            }
        }
        List<ToolGuardRuleEntity> seededRules = guardRuleMapper.selectList(
                new LambdaQueryWrapper<ToolGuardRuleEntity>().likeRight(ToolGuardRuleEntity::getRuleId, "channel_tool:"));
        List<Long> staleRuleIds = seededRules.stream()
                .filter(r -> !liveChannelToolNames.contains(r.getToolName()))
                .map(ToolGuardRuleEntity::getId)
                .toList();
        if (!staleRuleIds.isEmpty()) {
            guardRuleMapper.delete(new LambdaQueryWrapper<ToolGuardRuleEntity>().in(ToolGuardRuleEntity::getId, staleRuleIds));
            try {
                guardRuleRegistry.reload();
            } catch (Exception e) {
                log.debug("[channel-tool] guard rule reload after sweep failed (non-fatal): {}", e.getMessage());
            }
            log.info("[channel-tool] Swept {} orphan mate_tool_guard_rule row(s)", staleRuleIds.size());
        }
    }

    private void deleteToolRows(Long channelId) {
        int deleted = toolMapper.delete(
                new LambdaQueryWrapper<ToolEntity>().eq(ToolEntity::getChannelId, channelId));
        if (deleted > 0) {
            log.info("[channel-tool] Deleted {} mate_tool row(s) for channel {}", deleted, channelId);
        }
    }

    private boolean isToolRowEnabled(String actualName) {
        ToolEntity row = toolMapper.selectOne(
                new LambdaQueryWrapper<ToolEntity>().eq(ToolEntity::getName, actualName));
        return row != null && Boolean.TRUE.equals(row.getEnabled());
    }

    private Map<String, Object> parseConfig(String configJson) {
        if (configJson == null || configJson.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(configJson, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("[channel-tool] Failed to parse configJson: {}", e.getMessage());
            return Map.of();
        }
    }

    // ---- test inspection ----

    int registeredChannelCount() { return registered.size(); }

    Map<String, ChannelToolProvider> providersByTypeForTest() { return providersByType; }
}
