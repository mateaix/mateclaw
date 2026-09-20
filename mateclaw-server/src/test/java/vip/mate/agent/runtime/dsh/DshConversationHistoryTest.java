package vip.mate.agent.runtime.dsh;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.session.SqlSession;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.agent.context.TokenEstimator;
import vip.mate.workspace.conversation.repository.MessageMapper;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DshConversationHistoryTest {
    private JdbcTemplate jdbc;
    private SqlSession session;
    private DshConversationHistory history;

    @BeforeEach
    void setup() throws Exception {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:dsh_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE mate_message (id BIGINT PRIMARY KEY, conversation_id VARCHAR(100), role VARCHAR(20), content CLOB, status VARCHAR(20), deleted INT DEFAULT 0, create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        var configuration = new MybatisConfiguration();
        configuration.addMapper(MessageMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(configuration);
        session = factory.getObject().openSession(true);
        history = new DshConversationHistory(session.getMapper(MessageMapper.class), new ObjectMapper());
    }

    @AfterEach
    void close() {
        if (session != null) session.close();
        if (jdbc != null) jdbc.execute("DROP ALL OBJECTS");
    }

    @Test
    void secondTurnIncludesEarlierUserAndAssistantButNotCurrentOrFutureRows() {
        row(1, "a", "user", "我的名字叫小明", "completed");
        row(2, "a", "assistant", "你好，小明", "completed");
        row(3, "a", "user", "我叫什么名字？", "completed");
        row(4, "a", "assistant", "future answer", "completed");
        String prompt = history.enrich("a", "我叫什么名字？", "enriched current question",
                ChatOrigin.EMPTY.withOriginMessageId(3L));
        assertTrue(prompt.contains("我的名字叫小明"));
        assertTrue(prompt.contains("你好，小明"));
        assertTrue(prompt.indexOf("我的名字叫小明") < prompt.indexOf("你好，小明"));
        assertFalse(prompt.contains("我叫什么名字？"));
        assertFalse(prompt.contains("future answer"));
        assertTrue(prompt.endsWith("enriched current question"));
    }

    @Test
    void isolatesConversationsAndExcludesDeletedFailedAndNonDialogueRows() {
        row(1, "other", "user", "private secret", "completed");
        row(2, "a", "assistant", "failure placeholder", "failed");
        row(3, "a", "assistant", "still generating", "generating");
        row(4, "a", "tool", "tool internals", "completed");
        row(5, "a", "system", "system marker", "completed");
        row(6, "a", "user", "deleted text", "completed");
        jdbc.update("UPDATE mate_message SET deleted=1 WHERE id=6");
        assertEquals("question", history.enrich("a", "question", "question", ChatOrigin.EMPTY));
        assertEquals("question", history.enrich("new", "question", "question", ChatOrigin.EMPTY));
    }

    @Test
    void missingOriginOnlyDeduplicatesMatchingTrailingUserMessage() {
        row(1, "a", "user", "repeat", "completed");
        row(2, "a", "assistant", "previous answer", "completed");
        row(3, "a", "user", "repeat", "completed");
        String prompt = history.enrich("a", "repeat", "current enriched", ChatOrigin.EMPTY);
        assertEquals(1, prompt.split("repeat", -1).length - 1);
        assertTrue(prompt.contains("previous answer"));
    }

    @Test
    void nonPersistedInputDoesNotDropLastHistoricalMessage() {
        row(1, "a", "user", "earlier question", "completed");
        String prompt = history.enrich("a", "new question", "new question", null);
        assertTrue(prompt.contains("earlier question"));
    }

    @Test
    void boundedHistoryKeepsNewestContextAndCurrentInputIntact() {
        row(1, "a", "user", "oldest secret", "completed");
        row(2, "a", "assistant", "界".repeat(9000), "completed");
        row(3, "a", "user", "recent useful fact", "completed");
        String prompt = history.enrich("a", "question", "question", ChatOrigin.EMPTY);
        assertTrue(prompt.contains("recent useful fact"));
        assertFalse(prompt.contains("oldest secret"));
        assertTrue(prompt.contains("[truncated]"));
        assertTrue(TokenEstimator.estimateTokens(prompt) <= 4096 + TokenEstimator.estimateTokens("question"));
        assertTrue(prompt.endsWith("question"));
    }

    @Test
    void limitsHistoryRowsAndSurvivesNewBuilderInstance() {
        for (int i = 1; i <= 45; i++) row(i, "a", "user", "row-" + i + "-text", "completed");
        var restarted = new DshConversationHistory(session.getMapper(MessageMapper.class), new ObjectMapper());
        String prompt = restarted.enrich("a", "question", "question", ChatOrigin.EMPTY);
        assertFalse(prompt.contains("row-5-text"));
        assertTrue(prompt.contains("row-6-text"));
        assertTrue(prompt.contains("row-45-text"));
    }

    @Test
    void skipsScheduledTasks() {
        row(1, "a", "user", "previous job", "completed");
        ChatOrigin cron = new ChatOrigin(null, "a", null, null, null, null, null, true,
                null, null, null, null, null);
        assertEquals("next job", history.enrich("a", "next job", "next job", cron));
    }

    private void row(long id, String conversation, String role, String content, String status) {
        jdbc.update("INSERT INTO mate_message(id,conversation_id,role,content,status) VALUES(?,?,?,?,?)",
                id, conversation, role, content, status);
    }
}
