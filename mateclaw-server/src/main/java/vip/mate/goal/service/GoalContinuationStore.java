package vip.mate.goal.service;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

/** Durable scheduling state. Every worker write is fenced by its unique lease token. */
@Repository
public class GoalContinuationStore {
    private final JdbcTemplate jdbc;
    private static final String ELIGIBLE = """
            g.status='active' AND g.deleted=0 AND g.persistent_execution=TRUE
            AND g.auto_followup_enabled=TRUE
            """;
    private static final String DUE = """
            ((c.state IN ('queued','retry') AND c.next_run_at<=?)
             OR (c.state='running' AND c.lease_until<=?))
            """;

    public GoalContinuationStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Continuation(Long goalId, String conversationId, String state,
                               LocalDateTime nextRunAt, String leaseOwner,
                               LocalDateTime leaseUntil, int failures, String reason,
                               String currentAttemptId, long revision) {
        public Continuation(Long goalId, String conversationId, String state,
                            LocalDateTime nextRunAt, String leaseOwner,
                            LocalDateTime leaseUntil, int failures, String reason) {
            this(goalId, conversationId, state, nextRunAt, leaseOwner, leaseUntil,
                    failures, reason, null, 0);
        }
    }

    public void discover(LocalDateTime now) {
        // Bounded discovery; another instance may insert the same goal concurrently.
        List<Long> ids = jdbc.queryForList("""
                SELECT g.id FROM mate_agent_goal g WHERE
                """ + ELIGIBLE + """
                AND NOT EXISTS(SELECT 1 FROM mate_goal_continuation c WHERE c.goal_id=g.id)
                ORDER BY g.id LIMIT 100
                """, Long.class);
        for (Long id : ids) {
            try {
                jdbc.update("""
                        INSERT INTO mate_goal_continuation(goal_id,state,next_run_at,failures,reason,updated_at)
                        VALUES(?,'queued',?,0,'goal_active',?)
                        """, id, now, now);
            } catch (DuplicateKeyException ignored) { /* the other instance owns discovery */ }
        }
    }

    public List<Continuation> due(LocalDateTime now, int limit) {
        return jdbc.query("""
                SELECT c.*,g.conversation_id FROM mate_goal_continuation c
                JOIN mate_agent_goal g ON g.id=c.goal_id WHERE
                """ + ELIGIBLE + " AND " + DUE + " ORDER BY c.next_run_at,c.goal_id LIMIT ?",
                (rs, row) -> read(rs), now, now, Math.max(1, Math.min(limit, 100)));
    }

    public Continuation get(Long goalId) {
        List<Continuation> rows = jdbc.query("""
                SELECT c.*,g.conversation_id FROM mate_goal_continuation c
                JOIN mate_agent_goal g ON g.id=c.goal_id WHERE c.goal_id=?
                """, (rs, row) -> read(rs), goalId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public boolean claim(Long goalId, String token, LocalDateTime now, LocalDateTime until) {
        return jdbc.update("""
                UPDATE mate_goal_continuation SET state='running',lease_owner=?,lease_until=?,updated_at=?,
                wake_requested=FALSE,revision=revision+1
                WHERE goal_id=? AND
                ((state IN ('queued','retry') AND next_run_at<=?)
                 OR (state='running' AND lease_until<=?))
                AND EXISTS(SELECT 1 FROM mate_agent_goal g WHERE g.id=goal_id AND
                """ + ELIGIBLE + ")", token, until, now, goalId, now, now) == 1;
    }

    public boolean renew(Long goalId, String token, LocalDateTime until) {
        return jdbc.update("""
                UPDATE mate_goal_continuation SET lease_until=?
                WHERE goal_id=? AND lease_owner=? AND state='running'
                """, until, goalId, token) == 1;
    }

    public boolean bindAttempt(Long goalId, String token, String attemptId, long expectedRevision) {
        return jdbc.update("""
                UPDATE mate_goal_continuation SET current_attempt_id=?,revision=revision+1,updated_at=?
                WHERE goal_id=? AND lease_owner=? AND state='running'
                AND current_attempt_id IS NULL AND revision=?
                """, attemptId, LocalDateTime.now(), goalId, token, expectedRevision) == 1;
    }

    public boolean matchesFence(Long goalId, String token, String attemptId, long revision) {
        Integer count=jdbc.queryForObject("""
                SELECT COUNT(*) FROM mate_goal_continuation
                WHERE goal_id=? AND lease_owner=? AND current_attempt_id=?
                AND revision=? AND state='running'
                """,Integer.class,goalId,token,attemptId,revision);
        return count!=null && count==1;
    }

    public boolean renewFenced(Long goalId,String token,String attemptId,long revision,LocalDateTime until) {
        return jdbc.update("""
                UPDATE mate_goal_continuation SET lease_until=?,updated_at=?
                WHERE goal_id=? AND lease_owner=? AND current_attempt_id=?
                AND revision=? AND state='running'
                """,until,LocalDateTime.now(),goalId,token,attemptId,revision)==1;
    }

    public boolean settleFenced(Long goalId,String token,String attemptId,long revision,String state,
                                LocalDateTime nextRunAt,int failures,String reason,LocalDateTime now) {
        return jdbc.update("""
                UPDATE mate_goal_continuation
                SET state=CASE WHEN ?='waiting_approval' AND wake_requested=TRUE THEN 'queued' ELSE ? END,
                next_run_at=?,failures=?,reason=?,wake_requested=FALSE,lease_owner=NULL,lease_until=NULL,
                current_attempt_id=NULL,revision=revision+1,updated_at=?
                WHERE goal_id=? AND lease_owner=? AND current_attempt_id=? AND revision=? AND state='running'
                """,state,state,nextRunAt,failures,bounded(reason),now,goalId,token,attemptId,revision)==1;
    }

    public boolean recoverExpired(Long goalId,String token,String attemptId,LocalDateTime expiredAt,
                                  String state,LocalDateTime nextRunAt,int failures,String reason,LocalDateTime now) {
        return jdbc.update("""
                UPDATE mate_goal_continuation
                SET state=?,next_run_at=?,failures=?,reason=?,wake_requested=FALSE,
                lease_owner=NULL,lease_until=NULL,current_attempt_id=NULL,revision=revision+1,updated_at=?
                WHERE goal_id=? AND lease_owner=? AND current_attempt_id=? AND state='running'
                AND lease_until<=?
                """,state,nextRunAt,failures,bounded(reason),now,goalId,token,attemptId,expiredAt)==1;
    }

    public boolean settle(Long goalId, String token, String state, LocalDateTime nextRunAt,
                          int failures, String reason) {
        return jdbc.update("""
                UPDATE mate_goal_continuation SET state=CASE WHEN ?='waiting_approval' AND wake_requested=TRUE THEN 'queued' ELSE ? END,
                next_run_at=?,failures=?,reason=?,wake_requested=FALSE,lease_owner=NULL,lease_until=NULL,updated_at=?
                WHERE goal_id=? AND lease_owner=? AND state='running'
                """, state, state, nextRunAt, failures, bounded(reason), LocalDateTime.now(), goalId, token) == 1;
    }

    public void suspendConversation(String conversationId, String reason) {
        jdbc.update("""
                UPDATE mate_goal_continuation SET state='paused',reason=?,lease_owner=NULL,lease_until=NULL,
                current_attempt_id=NULL,revision=revision+1,updated_at=?
                WHERE goal_id IN (SELECT id FROM mate_agent_goal WHERE conversation_id=?)
                """, bounded(reason), LocalDateTime.now(), conversationId);
    }

    public void resume(Long goalId, LocalDateTime now) {
        jdbc.update("""
                UPDATE mate_goal_continuation SET state='queued',next_run_at=?,failures=0,reason='resumed',
                lease_owner=NULL,lease_until=NULL,current_attempt_id=NULL,revision=revision+1,updated_at=?
                WHERE goal_id=? AND state<>'running'
                """, now, now, goalId);
    }

    public void turnFinished(String conversationId, LocalDateTime now) {
        jdbc.update("""
                UPDATE mate_goal_continuation SET state=CASE WHEN state='waiting_approval' THEN 'queued' ELSE state END,
                wake_requested=TRUE,next_run_at=?,reason='interactive_turn_finished',revision=revision+1,updated_at=?
                WHERE state IN ('waiting_approval','running') AND goal_id IN
                (SELECT id FROM mate_agent_goal WHERE conversation_id=? AND status='active' AND deleted=0)
                """,now,now,conversationId);
    }

    private static Continuation read(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp until = rs.getTimestamp("lease_until");
        return new Continuation(rs.getLong("goal_id"), rs.getString("conversation_id"), rs.getString("state"),
                rs.getTimestamp("next_run_at").toLocalDateTime(), rs.getString("lease_owner"),
                until == null ? null : until.toLocalDateTime(), rs.getInt("failures"), rs.getString("reason"),
                rs.getString("current_attempt_id"),rs.getLong("revision"));
    }

    private static String bounded(String text) {
        return text == null ? "" : text.substring(0, Math.min(1000, text.length()));
    }
}
