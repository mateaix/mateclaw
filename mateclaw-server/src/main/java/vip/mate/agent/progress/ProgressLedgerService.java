package vip.mate.agent.progress;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.mate.workspace.conversation.model.ConversationEntity;
import vip.mate.workspace.conversation.repository.ConversationMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Loader / writer for the per-conversation progress ledger persisted as a
 * JSON blob on {@code mate_conversation.progress_ledger} (see V100 migration).
 *
 * <p>The service is the only component that touches the JSON column directly.
 * Callers above it work with {@link ProgressLedger} (immutable view) or plain
 * {@code Map<String, ProgressEntry>}.
 *
 * <p>Three entry classes share the JSON column:
 * <ul>
 *   <li><b>Regular entries</b> ({@code entries} map) — written by the LLM via
 *       {@code progress_update} tool through {@link #upsert}.</li>
 *   <li><b>Pinned entries</b> ({@code pinned} map) — written by Java via
 *       {@link #upsertPinned} when {@code load_skill} extracts structured
 *       constraints. Never touched by the LLM's {@code progress_update}.</li>
 *   <li><b>Auto-recorded entries</b> — stored in the {@code entries} map with
 *       a key prefixed by {@link ProgressLedger#AUTO_RECORDED_PREFIX}, written
 *       by Java via {@link #upsertAutoRecorded} after successful tool calls.
 *       Bounded to the most recent {@link #MAX_AUTO_RECORDED} entries.</li>
 * </ul>
 *
 * <p><b>JSON format</b> (backward-compatible): the new wrapper shape is
 * {@code {"entries": {...}, "pinned": {...}}}. Old conversations stored as
 * a flat map {@code {"step1": {...}}} are auto-migrated on first load —
 * the flat map is treated as {@code entries} with an empty {@code pinned}.
 *
 * <p>Failure mode: a malformed JSON value never throws back at the caller —
 * the runtime would rather render no snapshot than crash the reasoning loop
 * over a corrupted ledger column.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProgressLedgerService {

    /** Map<stepKey, ProgressEntry> — LinkedHashMap preserves insertion order. */
    private static final TypeReference<LinkedHashMap<String, ProgressEntry>> ENTRIES_TYPE =
            new TypeReference<>() {};

    /** Wrapper type for the new JSON format. */
    private static final TypeReference<LedgerWrapper> WRAPPER_TYPE = new TypeReference<>() {};

    /** Maximum auto-recorded entries kept per conversation (risk mitigation). */
    public static final int MAX_AUTO_RECORDED = 5;

    /**
     * Per-conversation lock for the load-mutate-save sequence inside
     * {@link #upsert}. See class Javadoc in ProgressLedger for the
     * virtual-thread pinning rationale.
     */
    private final ConcurrentHashMap<String, ReentrantLock> upsertLocks = new ConcurrentHashMap<>();

    private final ConversationMapper conversationMapper;
    private final ObjectMapper objectMapper;

    /**
     * Wrapper for the persisted JSON. Both fields default to empty maps
     * so a partially-written JSON (e.g. only entries) still parses.
     */
    public record LedgerWrapper(
            LinkedHashMap<String, ProgressEntry> entries,
            LinkedHashMap<String, ProgressEntry> pinned) {
        public LedgerWrapper() {
            this(new LinkedHashMap<>(), new LinkedHashMap<>());
        }
    }

    /**
     * @return the conversation's ledger, never null — an empty map when the
     *         column is NULL or unparseable.
     */
    public ProgressLedger load(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return ProgressLedger.empty();
        }
        return parse(loadLedgerJson(conversationId));
    }

    protected String loadLedgerJson(String conversationId) {
        ConversationEntity row = conversationMapper.selectOne(
                new LambdaQueryWrapper<ConversationEntity>()
                        .eq(ConversationEntity::getConversationId, conversationId)
                        .select(ConversationEntity::getProgressLedger));
        return row != null ? row.getProgressLedger() : null;
    }

    protected void saveLedgerJson(String conversationId, String json) {
        conversationMapper.update(null,
                new LambdaUpdateWrapper<ConversationEntity>()
                        .eq(ConversationEntity::getConversationId, conversationId)
                        .set(ConversationEntity::getProgressLedger, json));
    }

    /**
     * Upsert one regular entry on the ledger atomically (load → mutate → save).
     * Never touches pinned entries.
     *
     * @return the updated ledger so callers can render a fresh snapshot
     *         without a second DB roundtrip.
     */
    public ProgressLedger upsert(String conversationId, String key, String label,
                                 ProgressStatus status, String note) {
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("conversationId is required");
        }
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("step key is required");
        }
        // Guard reserved prefixes — the LLM must not overwrite Java-managed
        // entries (auto-recorded tool calls or pinned skill constraints).
        // Reject the write so the caller re-issues progress_update under a
        // non-reserved key instead of clobbering a system-managed entry.
        if (key.startsWith(ProgressLedger.AUTO_RECORDED_PREFIX) || key.startsWith("pin_")) {
            throw new IllegalArgumentException(
                    "step key prefix '" + ProgressLedger.AUTO_RECORDED_PREFIX
                            + "' / 'pin_' is reserved for system-managed entries; "
                            + "use a different key like 'step_<name>'");
        }
        if (status == null) {
            throw new IllegalArgumentException("status is required");
        }
        ReentrantLock lock = upsertLocks.computeIfAbsent(conversationId, k -> new ReentrantLock());
        lock.lock();
        try {
            LedgerWrapper wrapper = loadWrapper(conversationId);
            Map<String, ProgressEntry> map = wrapper.entries;
            ProgressEntry existing = map.get(key);
            String effectiveLabel = (label != null && !label.isBlank())
                    ? label
                    : (existing != null ? existing.getLabel() : key);
            map.put(key, new ProgressEntry(key, effectiveLabel, status, note, Instant.now()));
            persistWrapper(conversationId, wrapper);
            return new ProgressLedger(map, wrapper.pinned);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Upsert a pinned entry (Java-controlled, from skill constraints).
     * The LLM's {@code progress_update} tool never touches pinned entries.
     *
     * @param conversationId target conversation
     * @param key            stable key, e.g. {@code pin_<skillName>_<index>}
     * @param label          human-readable constraint text
     * @param note           optional extra context
     */
    public void upsertPinned(String conversationId, String key, String label, String note) {
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("conversationId is required");
        }
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("pinned key is required");
        }
        ReentrantLock lock = upsertLocks.computeIfAbsent(conversationId, k -> new ReentrantLock());
        lock.lock();
        try {
            LedgerWrapper wrapper = loadWrapper(conversationId);
            wrapper.pinned.put(key, new ProgressEntry(key, label, ProgressStatus.PENDING, note, Instant.now()));
            persistWrapper(conversationId, wrapper);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Remove all pinned entries whose key starts with the given prefix.
     * Used when a skill is unloaded or updated — the old constraints
     * should be cleared before re-injecting the new ones.
     */
    public void clearPinnedByPrefix(String conversationId, String keyPrefix) {
        if (conversationId == null || conversationId.isBlank() || keyPrefix == null) {
            return;
        }
        ReentrantLock lock = upsertLocks.computeIfAbsent(conversationId, k -> new ReentrantLock());
        lock.lock();
        try {
            LedgerWrapper wrapper = loadWrapper(conversationId);
            wrapper.pinned.entrySet().removeIf(e -> e.getKey() != null && e.getKey().startsWith(keyPrefix));
            persistWrapper(conversationId, wrapper);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Auto-record a completed tool call as a ledger entry (B5). Uses the
     * {@link ProgressLedger#AUTO_RECORDED_PREFIX} on the key so the renderer
     * groups it separately. Bounds the total auto-recorded entries to
     * {@link #MAX_AUTO_RECORDED} by evicting the oldest.
     *
     * <p>Does NOT overwrite an existing entry with the same key — if the
     * LLM already tracked this step via {@code progress_update}, the LLM's
     * entry stays. This prevents Java from clobbering a richer LLM-authored
     * note.
     *
     * @param conversationId target conversation
     * @param toolName       unique tool identifier used as the key suffix —
     *                       for MCP tools this should be the FULL prefixed
     *                       name ({@code mcp_<serverId>_<slug>_<hash6>}) to
     *                       avoid collisions between servers that have tools
     *                       with the same slug.
     * @param displayName    human-readable label shown in the snapshot (e.g.
     *                       the slug portion only). Falls back to
     *                       {@code toolName} when null/blank.
     * @param resultSummary  short tool-result excerpt; truncated to 120 chars
     */
    public void upsertAutoRecorded(String conversationId, String toolName, String displayName,
                                   String resultSummary) {
        if (conversationId == null || conversationId.isBlank() || toolName == null || toolName.isBlank()) {
            return;
        }
        upsertAutoRecordedBatch(conversationId,
                List.of(new AutoRecordEntry(toolName, displayName, resultSummary)));
    }

    /**
     * Input tuple for batch auto-record: the unique tool name (key suffix),
     * the readable display label, and the truncated result summary.
     */
    public record AutoRecordEntry(String toolName, String displayName, String resultSummary) {}

    /**
     * Batch version of {@link #upsertAutoRecorded} — processes multiple tool
     * results in a single lock + load + mutate + save cycle. Use this when
     * ActionNode receives a batch of parallel ToolResponses to avoid
     * serializing N lock acquisitions.
     *
     * <p>Skips entries whose {@code toolName} is null/blank or whose key
     * already exists in the ledger (LLM-authored entries are preserved).
     * Bounds the total auto-recorded entries to {@link #MAX_AUTO_RECORDED}
     * by evicting the oldest in bulk after inserting the new batch.
     */
    public void upsertAutoRecordedBatch(String conversationId, List<AutoRecordEntry> entries) {
        if (conversationId == null || conversationId.isBlank()
                || entries == null || entries.isEmpty()) {
            return;
        }
        ReentrantLock lock = upsertLocks.computeIfAbsent(conversationId, k -> new ReentrantLock());
        lock.lock();
        try {
            LedgerWrapper wrapper = loadWrapper(conversationId);
            Map<String, ProgressEntry> map = wrapper.entries;
            Instant now = Instant.now();
            for (AutoRecordEntry e : entries) {
                if (e == null || e.toolName() == null || e.toolName().isBlank()) {
                    continue;
                }
                String key = ProgressLedger.AUTO_RECORDED_PREFIX + e.toolName();
                // Don't overwrite an LLM-authored entry (LLM wouldn't use the auto_ prefix).
                if (map.containsKey(key)) {
                    continue;
                }
                String label = (e.displayName() != null && !e.displayName().isBlank())
                        ? e.displayName() : e.toolName();
                String note = e.resultSummary();
                if (note != null && note.length() > 120) {
                    note = note.substring(0, 120) + "…";
                }
                map.put(key, new ProgressEntry(key, label, ProgressStatus.DONE, note, now));
            }
            // Bound auto-recorded entries: evict oldest in bulk if over limit.
            List<String> autoKeys = new java.util.ArrayList<>();
            for (String k : map.keySet()) {
                if (k != null && k.startsWith(ProgressLedger.AUTO_RECORDED_PREFIX)) {
                    autoKeys.add(k);
                }
            }
            while (autoKeys.size() > MAX_AUTO_RECORDED && !autoKeys.isEmpty()) {
                String oldest = autoKeys.remove(0);
                map.remove(oldest);
            }
            persistWrapper(conversationId, wrapper);
        } finally {
            lock.unlock();
        }
    }

    // ==================== Internal: load / parse / persist ====================

    private ProgressLedger parse(String json) {
        if (json == null || json.isBlank() || "{}".equals(json.trim())) {
            return ProgressLedger.empty();
        }
        try {
            LedgerWrapper wrapper = parseWrapper(json);
            return new ProgressLedger(wrapper.entries, wrapper.pinned);
        } catch (Exception e) {
            log.warn("Failed to parse progress ledger JSON, treating as empty: {}", e.getMessage());
            return ProgressLedger.empty();
        }
    }

    /**
     * Parse with backward compatibility: new format has {@code "entries"}
     * and {@code "pinned"} keys; old format is a flat map treated as entries.
     */
    private LedgerWrapper parseWrapper(String json) throws Exception {
        // Peek: if the JSON contains '"entries"' it's the new wrapper format.
        if (json.contains("\"entries\"")) {
            return objectMapper.readValue(json, WRAPPER_TYPE);
        }
        // Old format: flat map → migrate to wrapper with empty pinned.
        LinkedHashMap<String, ProgressEntry> entries = objectMapper.readValue(json, ENTRIES_TYPE);
        return new LedgerWrapper(entries, new LinkedHashMap<>());
    }

    private LedgerWrapper loadWrapper(String conversationId) {
        String json = loadLedgerJson(conversationId);
        if (json == null || json.isBlank() || "{}".equals(json.trim())) {
            return new LedgerWrapper();
        }
        try {
            return parseWrapper(json);
        } catch (Exception e) {
            log.warn("Failed to parse progress ledger JSON for {}, treating as empty: {}",
                    conversationId, e.getMessage());
            return new LedgerWrapper();
        }
    }

    private void persistWrapper(String conversationId, LedgerWrapper wrapper) {
        try {
            String json = objectMapper.writeValueAsString(wrapper);
            saveLedgerJson(conversationId, json);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to persist progress ledger for " + conversationId + ": " + e.getMessage(), e);
        }
    }
}
