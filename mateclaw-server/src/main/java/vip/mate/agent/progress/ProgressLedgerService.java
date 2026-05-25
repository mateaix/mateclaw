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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loader / writer for the per-conversation progress ledger persisted as a
 * JSON blob on {@code mate_conversation.progress_ledger} (see V100 migration).
 *
 * <p>The service is the only component that touches the JSON column directly.
 * Callers above it work with {@link ProgressLedger} (immutable view) or plain
 * {@code Map<String, ProgressEntry>}.
 *
 * <p>Failure mode: a malformed JSON value never throws back at the caller —
 * the runtime would rather render no snapshot than crash the reasoning loop
 * over a corrupted ledger column. Parse failures are logged at warn level so
 * the operator notices on a long-running deployment.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProgressLedgerService {

    /** Map<stepKey, ProgressEntry> — LinkedHashMap preserves insertion order in the rendered snapshot. */
    private static final TypeReference<LinkedHashMap<String, ProgressEntry>> LEDGER_TYPE =
            new TypeReference<>() {};

    /**
     * Per-conversation mutex for the load-mutate-save sequence inside
     * {@link #upsert}. Without this guard, a single agent turn that issues
     * N parallel {@code progress_update} tool calls (observed: 12 calls in
     * one batch when the model pre-registered every step at task start)
     * collapses to last-writer-wins, losing every entry but one — defeating
     * the whole point of the ledger. Different conversations stay
     * uncontended; only intra-conversation writes serialise.
     *
     * <p>Entries are computed on demand and never explicitly removed; even
     * with thousands of long-running conversations the map stays bounded by
     * the active conversation set, and any leak is a {@code Object} per
     * conversation id — small enough to ignore relative to the rest of the
     * per-conv state already held in memory.
     */
    private final ConcurrentHashMap<String, Object> upsertLocks = new ConcurrentHashMap<>();

    private final ConversationMapper conversationMapper;
    private final ObjectMapper objectMapper;

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

    /**
     * Read the raw JSON column for one conversation, or {@code null} when
     * the row or column is empty. Protected so concurrency tests can
     * subclass and back the service with an in-memory map without having
     * to mock the Mybatis-Plus wrapper internals.
     */
    protected String loadLedgerJson(String conversationId) {
        ConversationEntity row = conversationMapper.selectOne(
                new LambdaQueryWrapper<ConversationEntity>()
                        .eq(ConversationEntity::getConversationId, conversationId)
                        .select(ConversationEntity::getProgressLedger));
        return row != null ? row.getProgressLedger() : null;
    }

    /**
     * Write the raw JSON column for one conversation. Protected for the
     * same reason as {@link #loadLedgerJson}.
     */
    protected void saveLedgerJson(String conversationId, String json) {
        conversationMapper.update(null,
                new LambdaUpdateWrapper<ConversationEntity>()
                        .eq(ConversationEntity::getConversationId, conversationId)
                        .set(ConversationEntity::getProgressLedger, json));
    }

    /**
     * Upsert one entry on the ledger atomically (load → mutate → save).
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
        if (status == null) {
            throw new IllegalArgumentException("status is required");
        }
        // Serialise the load-mutate-save sequence per conversation. Without
        // this, two parallel @Tool calls on the same conversation race: both
        // read the same starting state, each adds its own entry, and the
        // last save() drops the other's entry. Observed in production: a
        // 12-entry pre-registration collapsed to 8 because four sibling
        // tool calls landed in the same window.
        Object mutex = upsertLocks.computeIfAbsent(conversationId, k -> new Object());
        synchronized (mutex) {
            ProgressLedger ledger = load(conversationId);
            Map<String, ProgressEntry> map = ledger.asMap();
            ProgressEntry existing = map.get(key);
            String effectiveLabel = (label != null && !label.isBlank())
                    ? label
                    : (existing != null ? existing.getLabel() : key);
            map.put(key, new ProgressEntry(key, effectiveLabel, status, note, Instant.now()));
            persist(conversationId, map);
            return new ProgressLedger(map);
        }
    }

    private ProgressLedger parse(String json) {
        if (json == null || json.isBlank() || "{}".equals(json.trim())) {
            return ProgressLedger.empty();
        }
        try {
            LinkedHashMap<String, ProgressEntry> map = objectMapper.readValue(json, LEDGER_TYPE);
            return new ProgressLedger(map);
        } catch (Exception e) {
            log.warn("Failed to parse progress ledger JSON, treating as empty: {}", e.getMessage());
            return ProgressLedger.empty();
        }
    }

    private void persist(String conversationId, Map<String, ProgressEntry> map) {
        try {
            String json = objectMapper.writeValueAsString(map);
            saveLedgerJson(conversationId, json);
        } catch (Exception e) {
            // Surface to caller so the tool can return an error message to
            // the LLM rather than silently dropping the update.
            throw new IllegalStateException(
                    "Failed to persist progress ledger for " + conversationId + ": " + e.getMessage(), e);
        }
    }
}
