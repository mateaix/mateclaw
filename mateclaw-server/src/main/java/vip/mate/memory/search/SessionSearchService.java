package vip.mate.memory.search;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Session search service — full-text search over conversation history.
 * <p>
 * Dual-strategy:
 * - MySQL: FULLTEXT index with MATCH ... AGAINST
 * - H2: LIKE fallback for dev mode
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionSearchService {

    private final JdbcTemplate jdbcTemplate;
    private final DataSource dataSource;

    private volatile Boolean isMySql;

    /**
     * Search messages across conversations for the given agent.
     * Excludes the current conversation AND any conversation currently in
     * 'running' stream status, so that concurrent sibling conversations of
     * the same agent don't leak in-progress context into each other.
     */
    public List<SessionSearchResult> search(Long agentId, String currentConversationId,
                                            String query, int limit) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        int effectiveLimit = Math.min(Math.max(limit, 1), 50);
        String excludeConvId = currentConversationId != null ? currentConversationId : "";

        try {
            if (isMySql()) {
                return searchMySQL(agentId, excludeConvId, query, effectiveLimit);
            } else {
                return searchH2(agentId, excludeConvId, query, effectiveLimit);
            }
        } catch (Exception e) {
            log.warn("[SessionSearch] Search failed, falling back to LIKE: {}", e.getMessage());
            return searchH2(agentId, excludeConvId, query, effectiveLimit);
        }
    }

    /**
     * List recent conversations for the given agent.
     * Excludes the current conversation and any conversation currently in
     * 'running' stream status to avoid surfacing concurrent in-flight
     * conversations.
     */
    public List<Map<String, Object>> listRecent(Long agentId, String currentConversationId, int limit) {
        int effectiveLimit = Math.min(Math.max(limit, 1), 50);
        String excludeConvId = currentConversationId != null && !currentConversationId.isBlank()
                ? currentConversationId : "";

        String sql = """
                SELECT conversation_id, title, message_count, last_active_time, create_time
                FROM mate_conversation
                WHERE agent_id = ? AND deleted = 0
                  AND (stream_status IS NULL OR stream_status != 'running')
                  AND (? = '' OR conversation_id != ?)
                ORDER BY last_active_time DESC
                LIMIT ?
                """;

        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("conversationId", rs.getString("conversation_id"));
            row.put("title", rs.getString("title"));
            row.put("messageCount", rs.getInt("message_count"));
            row.put("lastActiveTime", toLocalDateTime(rs.getTimestamp("last_active_time")));
            row.put("createTime", toLocalDateTime(rs.getTimestamp("create_time")));
            return row;
        }, agentId, excludeConvId, excludeConvId, effectiveLimit);
    }

    // ==================== MySQL FULLTEXT ====================

    private List<SessionSearchResult> searchMySQL(Long agentId, String currentConversationId,
                                                   String query, int limit) {
        // 排除当前会话 + 排除正在运行中的并发兄弟会话，避免上下文串台
        String sql = """
                SELECT m.conversation_id, m.role, m.content, m.create_time,
                       c.title,
                       MATCH(m.content) AGAINST(? IN NATURAL LANGUAGE MODE) AS relevance
                FROM mate_message m
                JOIN mate_conversation c ON m.conversation_id = c.conversation_id
                WHERE c.agent_id = ? AND m.conversation_id != ?
                  AND m.role IN ('user', 'assistant')
                  AND m.deleted = 0 AND c.deleted = 0
                  AND (c.stream_status IS NULL OR c.stream_status != 'running')
                  AND MATCH(m.content) AGAINST(? IN NATURAL LANGUAGE MODE)
                ORDER BY relevance DESC
                LIMIT ?
                """;

        return jdbcTemplate.query(sql, (rs, rowNum) -> mapResult(rs, query),
                query, agentId, currentConversationId, query, limit);
    }

    // ==================== H2 LIKE fallback ====================

    private List<SessionSearchResult> searchH2(Long agentId, String currentConversationId,
                                                String query, int limit) {
        // Escape SQL LIKE special chars
        String escapedQuery = query.replace("%", "\\%").replace("_", "\\_");

        // 排除当前会话 + 排除正在运行中的并发兄弟会话，避免上下文串台
        String sql = """
                SELECT m.conversation_id, m.role, m.content, m.create_time, c.title
                FROM mate_message m
                JOIN mate_conversation c ON m.conversation_id = c.conversation_id
                WHERE c.agent_id = ? AND m.conversation_id != ?
                  AND m.role IN ('user', 'assistant')
                  AND m.deleted = 0 AND c.deleted = 0
                  AND (c.stream_status IS NULL OR c.stream_status != 'running')
                  AND LOWER(m.content) LIKE LOWER(CONCAT('%', ?, '%'))
                ORDER BY m.create_time DESC
                LIMIT ?
                """;

        return jdbcTemplate.query(sql, (rs, rowNum) -> mapResult(rs, query),
                agentId, currentConversationId, escapedQuery, limit);
    }

    // ==================== Helpers ====================

    private SessionSearchResult mapResult(ResultSet rs, String query) throws java.sql.SQLException {
        String content = rs.getString("content");
        String snippet = extractSnippet(content, query, 200);
        double relevance;
        try {
            relevance = rs.getDouble("relevance");
        } catch (Exception e) {
            relevance = 1.0; // H2 fallback has no relevance score
        }

        return new SessionSearchResult(
                rs.getString("conversation_id"),
                rs.getString("title"),
                snippet,
                rs.getString("role"),
                toLocalDateTime(rs.getTimestamp("create_time")),
                relevance
        );
    }

    /**
     * Extract a snippet centered around the query match, with context.
     */
    private String extractSnippet(String content, String query, int maxLength) {
        if (content == null || content.isBlank()) return "";
        if (content.length() <= maxLength) return content;

        int idx = content.toLowerCase().indexOf(query.toLowerCase());
        if (idx < 0) {
            return content.substring(0, maxLength) + "...";
        }

        int start = Math.max(0, idx - maxLength / 3);
        int end = Math.min(content.length(), start + maxLength);
        if (end - start < maxLength) {
            start = Math.max(0, end - maxLength);
        }

        StringBuilder sb = new StringBuilder();
        if (start > 0) sb.append("...");
        sb.append(content, start, end);
        if (end < content.length()) sb.append("...");
        return sb.toString();
    }

    private boolean isMySql() {
        if (isMySql == null) {
            try {
                String url = dataSource.getConnection().getMetaData().getURL();
                isMySql = url != null && url.contains("mysql");
            } catch (Exception e) {
                isMySql = false;
            }
        }
        return isMySql;
    }

    private LocalDateTime toLocalDateTime(Timestamp ts) {
        return ts != null ? ts.toLocalDateTime() : null;
    }
}
