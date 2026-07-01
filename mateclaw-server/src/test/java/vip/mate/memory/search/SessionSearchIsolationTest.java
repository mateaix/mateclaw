package vip.mate.memory.search;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import vip.mate.MateClawApplication;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Isolation regression test for {@link SessionSearchService}.
 * <p>
 * Concurrent sessions of the same agent must not leak into each other's
 * session_search results: a still-running sibling conversation
 * ({@code stream_status='running'}) and the caller's own current conversation
 * are both excluded from {@code listRecent} and {@code search}. Guards the fix
 * for cross-conversation memory contamination.
 */
@SpringBootTest(
        classes = MateClawApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:session_search_iso_${random.uuid};MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.ai.dashscope.api-key=test-key",
        "spring.main.web-application-type=none",
        "mateclaw.jwt.secret=session-search-iso-secret-0123456789"
})
class SessionSearchIsolationTest {

    private static final long AGENT_ID = 9_458_001L;
    private static final String CONV_CURRENT = "conv-current-458";
    private static final String CONV_COMPLETED = "conv-completed-458";
    private static final String CONV_RUNNING = "conv-running-458";

    @Autowired private SessionSearchService service;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM mate_message WHERE conversation_id IN (?, ?, ?)",
                CONV_CURRENT, CONV_COMPLETED, CONV_RUNNING);
        jdbc.update("DELETE FROM mate_conversation WHERE agent_id = ?", AGENT_ID);

        // Three sessions of the same agent: the caller's current one, a finished
        // sibling, and a still-running sibling.
        insertConversation(9_458_101L, CONV_CURRENT, "current chat", "idle");
        insertConversation(9_458_102L, CONV_COMPLETED, "yesterday nanjing weather", "idle");
        insertConversation(9_458_103L, CONV_RUNNING, "sibling running nanjing task", "running");

        insertMessage(9_458_201L, CONV_CURRENT, "user", "what is the weather");
        insertMessage(9_458_202L, CONV_COMPLETED, "assistant", "Nanjing is rainy today");
        insertMessage(9_458_203L, CONV_RUNNING, "assistant", "Nanjing forecast in progress");
    }

    private void insertConversation(long id, String convId, String title, String streamStatus) {
        jdbc.update("INSERT INTO mate_conversation (id, conversation_id, title, agent_id, message_count, "
                        + "last_active_time, stream_status, workspace_id, create_time, update_time, deleted) "
                        + "VALUES (?, ?, ?, ?, 1, CURRENT_TIMESTAMP, ?, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)",
                id, convId, title, AGENT_ID, streamStatus);
    }

    private void insertMessage(long id, String convId, String role, String content) {
        jdbc.update("INSERT INTO mate_message (id, conversation_id, role, content, status, "
                        + "create_time, update_time, deleted) "
                        + "VALUES (?, ?, ?, ?, 'completed', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)",
                id, convId, role, content);
    }

    @Test
    @DisplayName("listRecent excludes the running sibling and the current conversation")
    void listRecentExcludesRunningAndCurrent() {
        List<Map<String, Object>> recent = service.listRecent(AGENT_ID, CONV_CURRENT, 50);
        List<String> ids = recent.stream().map(r -> (String) r.get("conversationId")).toList();

        assertThat(ids).contains(CONV_COMPLETED);
        assertThat(ids).doesNotContain(CONV_RUNNING);   // still-running sibling must not leak
        assertThat(ids).doesNotContain(CONV_CURRENT);   // caller's own conversation excluded
    }

    @Test
    @DisplayName("search excludes the running sibling and the current conversation")
    void searchExcludesRunningAndCurrent() {
        List<SessionSearchResult> results = service.search(AGENT_ID, CONV_CURRENT, "Nanjing", 50);
        List<String> ids = results.stream().map(SessionSearchResult::conversationId).toList();

        assertThat(ids).contains(CONV_COMPLETED);
        assertThat(ids).doesNotContain(CONV_RUNNING);   // running sibling's match filtered out
        assertThat(ids).doesNotContain(CONV_CURRENT);
    }
}
