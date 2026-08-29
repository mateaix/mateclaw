package vip.mate.goal.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import vip.mate.goal.model.GoalAttempt;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Fenced persistence for bounded goal execution attempts. */
@Repository
public class GoalAttemptStore {
    private final JdbcTemplate jdbc;

    public GoalAttemptStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public GoalAttempt create(Long goalId, String conversationId, String parentAttemptId,
                              String triggerType, String leaseToken, LocalDateTime leaseUntil,
                              Long inputItemId, LocalDateTime now) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO mate_goal_attempt(
                    attempt_id,goal_id,conversation_id,parent_attempt_id,trigger_type,state,
                    lease_token,lease_until,input_item_id,replay_safety,checkpoint_type,
                    created_at,updated_at)
                VALUES(?,?,?,?,?,'claimed',?,?,?,'safe','claimed',?,?)
                """, id, goalId, conversationId, parentAttemptId, triggerType, leaseToken,
                leaseUntil, inputItemId, now, now);
        return get(id);
    }

    public GoalAttempt get(String id) {
        List<GoalAttempt> rows = jdbc.query("""
                SELECT * FROM mate_goal_attempt WHERE attempt_id=?
                """, (rs, row) -> read(rs), id);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public List<GoalAttempt> listRecent(Long goalId, int limit) {
        return jdbc.query("""
                SELECT * FROM mate_goal_attempt WHERE goal_id=?
                ORDER BY created_at DESC,attempt_id DESC LIMIT ?
                """, (rs, row) -> read(rs), goalId, Math.max(1, Math.min(limit, 100)));
    }

    public boolean markRunning(String id, String leaseToken, LocalDateTime now) {
        return jdbc.update("""
                UPDATE mate_goal_attempt SET state='running',started_at=?,updated_at=?
                WHERE attempt_id=? AND lease_token=? AND state='claimed'
                """, now, now, id, leaseToken) == 1;
    }

    public boolean renew(String id, String leaseToken, LocalDateTime leaseUntil,
                         LocalDateTime now) {
        return jdbc.update("""
                UPDATE mate_goal_attempt SET lease_until=?,updated_at=?
                WHERE attempt_id=? AND lease_token=? AND state IN ('claimed','running')
                """, leaseUntil, now, id, leaseToken) == 1;
    }

    public boolean checkpoint(String id, String leaseToken, String replaySafety,
                              String checkpointType, Long assistantMessageId,
                              LocalDateTime now) {
        return jdbc.update("""
                UPDATE mate_goal_attempt
                SET replay_safety=?,checkpoint_type=?,
                    assistant_message_id=COALESCE(?,assistant_message_id),updated_at=?
                WHERE attempt_id=? AND lease_token=? AND state IN ('claimed','running')
                """, replaySafety, checkpointType, assistantMessageId, now, id, leaseToken) == 1;
    }

    public boolean finish(String id, String leaseToken, String state, String finishReason,
                          String errorCategory, LocalDateTime now) {
        if (!GoalAttemptTerminalState.valid(state)) {
            throw new IllegalArgumentException("Unsupported terminal attempt state: " + state);
        }
        return jdbc.update("""
                UPDATE mate_goal_attempt
                SET state=?,finish_reason=?,error_category=?,finished_at=?,updated_at=?
                WHERE attempt_id=? AND lease_token=? AND state IN ('claimed','running')
                """, state, bounded(finishReason), bounded(errorCategory), now, now,
                id, leaseToken) == 1;
    }

    public List<GoalAttempt> expired(LocalDateTime now, int limit) {
        return jdbc.query("""
                SELECT * FROM mate_goal_attempt
                WHERE state IN ('claimed','running') AND lease_until<=?
                ORDER BY lease_until,created_at LIMIT ?
                """, (rs, row) -> read(rs), now, Math.max(1, Math.min(limit, 100)));
    }

    private static GoalAttempt read(ResultSet rs) throws SQLException {
        return new GoalAttempt(
                rs.getString("attempt_id"), rs.getLong("goal_id"),
                rs.getString("conversation_id"), rs.getString("parent_attempt_id"),
                rs.getString("trigger_type"), rs.getString("state"),
                rs.getString("lease_token"), time(rs, "lease_until"),
                nullableLong(rs, "input_item_id"), nullableLong(rs, "assistant_message_id"),
                rs.getString("replay_safety"), rs.getString("checkpoint_type"),
                rs.getString("finish_reason"), rs.getString("error_category"),
                time(rs, "started_at"), time(rs, "finished_at"),
                time(rs, "created_at"), time(rs, "updated_at"));
    }

    private static LocalDateTime time(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static String bounded(String text) {
        return text == null ? null : text.substring(0, Math.min(128, text.length()));
    }

    private static final class GoalAttemptTerminalState {
        private static boolean valid(String state) {
            return "succeeded".equals(state) || "retryable".equals(state)
                    || "blocked".equals(state) || "cancelled".equals(state);
        }
    }
}
