package vip.mate.context.intelligence.persist;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import vip.mate.context.intelligence.metrics.ContextIntelMetrics;
import vip.mate.context.intelligence.probe.WindowProbe;
import vip.mate.context.intelligence.probe.WindowProbeSnapshot;
import vip.mate.context.intelligence.probe.WindowStateLoader;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * DB read/write (JdbcTemplate).
 * <p>
 * Implements {@link WindowStateLoader} for {@code WindowProbeRegistry} to load persisted state.
 * <p>
 * <b>Table schema</b> (created in V161): {@code mate_model_context_state}, PK = (provider_id, model_name)
 * <p>
 * <b>Persistence strategy</b> (§5.6):
 * <ul>
 *   <li>Capture snapshot inside lock, write to DB outside lock (avoid IO inside lock)</li>
 *   <li>On failure, buffer to {@link PersistRetryQueue} and retry once on next signal</li>
 *   <li>Use "UPDATE first, INSERT if 0 rows" pattern, cross-dialect compatible</li>
 * </ul>
 *
 * @author MateClaw Team
 */
@Slf4j
@Component
public class WindowStateRepository implements WindowStateLoader {

    private static final String TABLE = "mate_model_context_state";

    private static final String SQL_UPDATE = """
            UPDATE """ + TABLE + """
            SET phase=?, effective_window=?, confidence_lower=?, confidence_upper=?,
                declared_limit=?, peak_observed=?, successive_success=?, successive_overflow=?,
                total_success=?, total_overflow=?, last_success_at=?, last_overflow_at=?, last_updated_at=?
            WHERE provider_id=? AND model_name=?
            """;

    private static final String SQL_INSERT = """
            INSERT INTO """ + TABLE + """
            (provider_id, model_name, phase, effective_window, confidence_lower, confidence_upper,
             declared_limit, peak_observed, successive_success, successive_overflow,
             total_success, total_overflow, last_success_at, last_overflow_at, last_updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String SQL_SELECT = """
            SELECT phase, effective_window, confidence_lower, confidence_upper,
                   declared_limit, peak_observed, successive_success, successive_overflow,
                   total_success, total_overflow, last_success_at, last_overflow_at, last_updated_at
            FROM """ + TABLE + """
            WHERE provider_id=? AND model_name=?
            """;

    private final JdbcTemplate jdbcTemplate;
    private final PersistRetryQueue retryQueue;
    /** Observability metrics gateway (§C.7), nullable: ObjectProvider may return null in test environment */
    private final ContextIntelMetrics metrics;

    public WindowStateRepository(JdbcTemplate jdbcTemplate,
                                  PersistRetryQueue retryQueue,
                                  ObjectProvider<ContextIntelMetrics> metricsProvider) {
        this.jdbcTemplate = jdbcTemplate;
        this.retryQueue = retryQueue;
        this.metrics = metricsProvider.getIfAvailable();
    }

    // ==================== WindowStateLoader ====================

    @Override
    public WindowProbeSnapshot load(String provider, String modelName) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(SQL_SELECT, provider, modelName);
            if (rows.isEmpty()) {
                return null;
            }
            Map<String, Object> row = rows.get(0);
            return mapToSnapshot(row);
        } catch (DataAccessException e) {
            log.debug("[ContextIntel] DB load failed for {}:{}: {}", provider, modelName, e.getMessage());
            return null;
        }
    }

    // ==================== Persistence ====================

    /**
     * Persist snapshot to DB (cross-dialect upsert: UPDATE first, INSERT if 0 rows).
     * <p>
     * On failure, buffer to {@link PersistRetryQueue}.
     */
    public void persist(String key, WindowProbeSnapshot snapshot) {
        String[] parts = splitKey(key);
        String provider = parts[0];
        String model = parts[1];

        try {
            int updated = jdbcTemplate.update(SQL_UPDATE,
                    snapshot.phase().name(),
                    snapshot.effectiveWindow(),
                    snapshot.confidenceLower(),
                    snapshot.confidenceUpper(),
                    snapshot.declaredLimit(),
                    snapshot.peakObserved(),
                    snapshot.successiveSuccess(),
                    snapshot.successiveOverflow(),
                    snapshot.totalSuccess(),
                    snapshot.totalOverflow(),
                    toTimestamp(snapshot.lastSuccessAt()),
                    toTimestamp(snapshot.lastOverflowAt()),
                    toTimestamp(snapshot.lastUpdatedAt()),
                    provider, model
            );
            if (updated == 0) {
                jdbcTemplate.update(SQL_INSERT,
                        provider, model,
                        snapshot.phase().name(),
                        snapshot.effectiveWindow(),
                        snapshot.confidenceLower(),
                        snapshot.confidenceUpper(),
                        snapshot.declaredLimit(),
                        snapshot.peakObserved(),
                        snapshot.successiveSuccess(),
                        snapshot.successiveOverflow(),
                        snapshot.totalSuccess(),
                        snapshot.totalOverflow(),
                        toTimestamp(snapshot.lastSuccessAt()),
                        toTimestamp(snapshot.lastOverflowAt()),
                        toTimestamp(snapshot.lastUpdatedAt())
                );
            }
        } catch (DataAccessException e) {
            log.debug("[ContextIntel] DB persist failed for {}: {}", key, e.getMessage());
            // §C.7: DB persist failure count
            if (metrics != null) {
                try {
                    metrics.recordDbPersistFailure(provider, model);
                } catch (Exception me) {
                    log.debug("[ContextIntel] dbPersistFailure metric failed: {}", me.getMessage());
                }
            }
            retryQueue.offer(key, snapshot);
        }
    }

    // ==================== Utilities ====================

    private WindowProbeSnapshot mapToSnapshot(Map<String, Object> row) {
        return new WindowProbeSnapshot(
                WindowProbe.Phase.valueOf(stringOf(row.get("phase"))),
                intOf(row.get("effective_window")),
                intOf(row.get("confidence_lower")),
                intOf(row.get("confidence_upper")),
                intOf(row.get("declared_limit")),
                intOf(row.get("peak_observed")),
                intOf(row.get("successive_success")),
                intOf(row.get("successive_overflow")),
                intOf(row.get("total_success")),
                intOf(row.get("total_overflow")),
                toInstant(row.get("last_success_at")),
                toInstant(row.get("last_overflow_at")),
                toInstant(row.get("last_updated_at"))
        );
    }

    private static String[] splitKey(String key) {
        int idx = key.indexOf(':');
        if (idx < 0) {
            return new String[]{key, ""};
        }
        return new String[]{key.substring(0, idx), key.substring(idx + 1)};
    }

    private static String stringOf(Object val) {
        return (val != null) ? val.toString() : null;
    }

    private static int intOf(Object val) {
        if (val == null) return 0;
        if (val instanceof Number n) return n.intValue();
        return Integer.parseInt(val.toString());
    }

    private static Timestamp toTimestamp(Instant instant) {
        return (instant != null) ? Timestamp.from(instant) : null;
    }

    private static Instant toInstant(Object val) {
        if (val == null) return null;
        if (val instanceof Timestamp ts) return ts.toInstant();
        if (val instanceof java.util.Date d) return d.toInstant();
        return null;
    }
}
