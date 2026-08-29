package vip.mate.channel.web;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import vip.mate.workspace.conversation.model.MessageContentPart;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** Database-backed FIFO for user input accepted while a conversation is busy. */
@Repository
public class ConversationInputQueueStore {
    private static final TypeReference<List<MessageContentPart>> PARTS_TYPE = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public ConversationInputQueueStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public QueuedInput enqueue(String conversationId, Long agentId, String createdBy,
                               String message, List<MessageContentPart> contentParts,
                               LocalDateTime now) {
        long id = IdWorker.getId();
        jdbc.update("""
                INSERT INTO mate_conversation_input_queue(
                    id,conversation_id,agent_id,created_by,message,content_parts,state,
                    created_at,updated_at)
                VALUES(?,?,?,?,?,?,'queued',?,?)
                """, id, conversationId, agentId, createdBy, message == null ? "" : message,
                writeParts(contentParts), now, now);
        return get(id);
    }

    public Optional<QueuedInput> claimNext(String conversationId, String attemptId,
                                           LocalDateTime now) {
        for (int tries = 0; tries < 8; tries++) {
            List<Long> ids = jdbc.queryForList("""
                    SELECT id FROM mate_conversation_input_queue
                    WHERE conversation_id=? AND state='queued' ORDER BY id LIMIT 1
                    """, Long.class, conversationId);
            if (ids.isEmpty()) return Optional.empty();
            long id = ids.getFirst();
            if (jdbc.update("""
                    UPDATE mate_conversation_input_queue
                    SET state='claimed',claimed_by_attempt_id=?,updated_at=?
                    WHERE id=? AND state='queued'
                    """, attemptId, now, id) == 1) {
                return Optional.of(get(id));
            }
        }
        return Optional.empty();
    }

    public boolean bindMessage(Long id, String attemptId, Long messageId, LocalDateTime now) {
        return jdbc.update("""
                UPDATE mate_conversation_input_queue
                SET persisted_message_id=COALESCE(persisted_message_id,?),updated_at=?
                WHERE id=? AND claimed_by_attempt_id=? AND state='claimed'
                """, messageId, now, id, attemptId) == 1;
    }

    public boolean consume(Long id, String attemptId, LocalDateTime now) {
        return jdbc.update("""
                UPDATE mate_conversation_input_queue SET state='consumed',updated_at=?
                WHERE id=? AND claimed_by_attempt_id=? AND state='claimed'
                """, now, id, attemptId) == 1;
    }

    public boolean release(Long id, String attemptId, LocalDateTime now) {
        return jdbc.update("""
                UPDATE mate_conversation_input_queue
                SET state='queued',claimed_by_attempt_id=NULL,updated_at=?
                WHERE id=? AND claimed_by_attempt_id=? AND state='claimed'
                """, now, id, attemptId) == 1;
    }

    public int releaseClaims(String attemptId,LocalDateTime now) {
        return jdbc.update("""
                UPDATE mate_conversation_input_queue
                SET state='queued',claimed_by_attempt_id=NULL,updated_at=?
                WHERE claimed_by_attempt_id=? AND state='claimed'
                """,now,attemptId);
    }

    public int releaseClaimsBefore(LocalDateTime cutoff,LocalDateTime now) {
        return jdbc.update("""
                UPDATE mate_conversation_input_queue
                SET state='queued',claimed_by_attempt_id=NULL,updated_at=?
                WHERE state='claimed' AND updated_at<=?
                """,now,cutoff);
    }

    public boolean cancel(Long id, String reason, LocalDateTime now) {
        return jdbc.update("""
                UPDATE mate_conversation_input_queue
                SET state='cancelled',cancel_reason=?,updated_at=?
                WHERE id=? AND state='queued'
                """, bounded(reason), now, id) == 1;
    }

    public QueuedInput get(Long id) {
        List<QueuedInput> rows = jdbc.query("""
                SELECT * FROM mate_conversation_input_queue WHERE id=?
                """, (rs, row) -> read(rs), id);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public List<QueuedInput> listQueued(String conversationId) {
        return jdbc.query("""
                SELECT * FROM mate_conversation_input_queue
                WHERE conversation_id=? AND state='queued' ORDER BY id
                """, (rs, row) -> read(rs), conversationId);
    }

    public int countQueued(String conversationId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM mate_conversation_input_queue
                WHERE conversation_id=? AND state='queued'
                """, Integer.class, conversationId);
        return count == null ? 0 : count;
    }

    private QueuedInput read(ResultSet rs) throws SQLException {
        return new QueuedInput(rs.getLong("id"), rs.getString("conversation_id"),
                nullableLong(rs, "agent_id"), rs.getString("created_by"),
                rs.getString("message"), readParts(rs.getString("content_parts")),
                rs.getString("state"), rs.getString("claimed_by_attempt_id"),
                nullableLong(rs, "persisted_message_id"), rs.getString("cancel_reason"),
                time(rs, "created_at"), time(rs, "updated_at"));
    }

    private String writeParts(List<MessageContentPart> parts) {
        try {
            return mapper.writeValueAsString(parts == null ? List.of() : parts);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("Queued input contains invalid content parts", error);
        }
    }

    private List<MessageContentPart> readParts(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return mapper.readValue(json, PARTS_TYPE);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Persisted queued input contains invalid content parts", error);
        }
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

    public record QueuedInput(
            Long id,
            String conversationId,
            Long agentId,
            String createdBy,
            String message,
            List<MessageContentPart> contentParts,
            String state,
            String claimedByAttemptId,
            Long persistedMessageId,
            String cancelReason,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {}
}
