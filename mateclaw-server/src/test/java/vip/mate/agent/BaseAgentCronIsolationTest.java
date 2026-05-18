package vip.mate.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.agent.context.ChatOriginHolder;
import vip.mate.workspace.conversation.ConversationService;
import vip.mate.workspace.conversation.model.MessageEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Issue #142 regression: a scheduled-job run must see only its own task in
 * the LLM prompt — never the prior or concurrent runs that share its
 * {@code tasks_<wsId>} (web-origin) or {@code cron_<id>} (per-job) conversation.
 *
 * <p>A cron run's instruction is passed explicitly through the call chain, so
 * the runtime never reconstructs it from the shared conversation: history is
 * empty and the current message is the explicit argument. This holds even
 * when concurrent runs interleave their rows into that conversation. A normal
 * Web / channel turn is unaffected — it still loads full history.
 */
class BaseAgentCronIsolationTest {

    @AfterEach
    void clearOrigin() {
        ChatOriginHolder.clear();
    }

    @Test
    @DisplayName("cron run: empty LLM history even when the conversation holds interleaved concurrent-run rows")
    void cronRun_emptyHistory_evenWithInterleavedRows() {
        ConversationService conv = mock(ConversationService.class);
        // The shared tasks_1 after three concurrent runs: headers, user rows
        // and assistant rows each cluster together — NOT adjacent per run,
        // because each startRun commits in its own interleaving transaction.
        List<MessageEntity> contaminated = List.of(
                sys("📋 job-A · 定时触发"), sys("📋 job-B · 定时触发"), sys("📋 job-C · 定时触发"),
                user("job A task"), user("job B task"), user("job C task"),
                assistant("A result"), assistant("B result"), assistant("C result"));
        when(conv.countMessages(any())).thenReturn((long) contaminated.size());
        when(conv.listMessages(any())).thenReturn(contaminated);
        stubRender(conv);

        TestAgent agent = newAgent(conv);
        ChatOriginHolder.set(ChatOrigin.cron("tasks_1", 1L, null, null, null));

        List<Message> history = agent.history("tasks_1", "job A task");

        assertTrue(history.isEmpty(),
                "a cron run must replay no conversation history at all");
    }

    @Test
    @DisplayName("cron run: current message is the explicit argument, never a conversation-guessed last user row")
    void cronRun_currentMessage_usesExplicitArgument() {
        ConversationService conv = mock(ConversationService.class);
        // The conversation's LAST user row belongs to a DIFFERENT concurrent
        // run — the pre-fix code reconstructed the current message from it.
        when(conv.listMessages(any())).thenReturn(List.of(
                sys("📋 job-A"), sys("📋 job-B"),
                user("job A task"), user("job B task — WRONG for this run")));
        stubRender(conv);

        TestAgent agent = newAgent(conv);
        ChatOriginHolder.set(ChatOrigin.cron("tasks_1", 1L, null, null, null));

        String current = agent.currentMessage("tasks_1", "job A task — the real one");

        assertEquals("job A task — the real one", current,
                "a cron run must use its own explicit task text, not the conversation's last user row");
    }

    @Test
    @DisplayName("normal turn: full history kept even when the conversation holds scheduled-job records")
    void nonCronTurn_keepsFullHistory() {
        ConversationService conv = mock(ConversationService.class);
        List<MessageEntity> stored = List.of(
                user("早上好"), assistant("你好，有什么可以帮你"), user("现在几点"));
        when(conv.countMessages("conv_x")).thenReturn((long) stored.size());
        when(conv.listMessages("conv_x")).thenReturn(stored);
        stubRender(conv);

        TestAgent agent = newAgent(conv);
        ChatOriginHolder.set(ChatOrigin.web("conv_x", "u1", 1L, null));

        List<Message> history = agent.history("conv_x", "现在几点");

        assertEquals(2, history.size(),
                "a normal turn keeps prior history (the trailing current user row is de-duplicated)");
    }

    // ---------- scaffold ----------

    private static TestAgent newAgent(ConversationService conv) {
        TestAgent agent = new TestAgent(conv);
        agent.agentName = "test-agent";
        agent.modelName = "test-model";
        return agent;
    }

    private static void stubRender(ConversationService conv) {
        when(conv.renderMessageContent(any())).thenAnswer(
                inv -> ((MessageEntity) inv.getArgument(0)).getContent());
    }

    private static MessageEntity row(String role, String content) {
        MessageEntity m = new MessageEntity();
        m.setRole(role);
        m.setContent(content);
        return m;
    }

    private static MessageEntity sys(String content) {
        return row("system", content);
    }

    private static MessageEntity user(String content) {
        return row("user", content);
    }

    private static MessageEntity assistant(String content) {
        return row("assistant", content);
    }

    /** Minimal concrete BaseAgent fixture exposing the protected builders. */
    static class TestAgent extends BaseAgent {
        TestAgent(ConversationService conv) {
            super(null, conv);
        }

        List<Message> history(String conversationId, String currentUserMessage) {
            return buildConversationHistory(conversationId, currentUserMessage);
        }

        String currentMessage(String conversationId, String userMessageText) {
            return buildCurrentUserMessageWithRouting(conversationId, userMessageText)
                    .userMessage().getText();
        }

        @Override public String chat(String userMessage, String conversationId) {
            throw new UnsupportedOperationException();
        }

        @Override public reactor.core.publisher.Flux<String> chatStream(String userMessage, String conversationId) {
            throw new UnsupportedOperationException();
        }

        @Override public String execute(String goal, String conversationId) {
            throw new UnsupportedOperationException();
        }
    }
}
