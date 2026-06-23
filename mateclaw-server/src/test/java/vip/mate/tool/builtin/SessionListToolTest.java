package vip.mate.tool.builtin;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.mate.agent.delegation.SubagentRegistry;
import vip.mate.workspace.conversation.model.ConversationEntity;
import vip.mate.workspace.conversation.repository.ConversationMapper;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SessionListTool} — the read-only "list" leg of the
 * spawn / send / list triad, with DB-backed discovery of persisted child
 * sessions overlaid by live registry status.
 *
 * @author MateClaw Team
 */
@ExtendWith(MockitoExtension.class)
class SessionListToolTest {

    @Mock ConversationMapper conversationMapper;

    private SubagentRegistry registry;
    private SessionListTool tool;

    @BeforeAll
    static void initMyBatisPlusCache() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new org.apache.ibatis.session.Configuration(), ""),
                ConversationEntity.class);
    }

    @BeforeEach
    void setUp() {
        registry = new SubagentRegistry();
        tool = new SessionListTool(registry, conversationMapper);
        while (DelegationContext.currentDepth() > 0) {
            DelegationContext.exit();
        }
        ToolExecutionContext.clear();
    }

    @AfterEach
    void tearDown() {
        ToolExecutionContext.clear();
        while (DelegationContext.currentDepth() > 0) {
            DelegationContext.exit();
        }
    }

    private static ConversationEntity child(String conversationId, Long agentId, String title) {
        ConversationEntity c = new ConversationEntity();
        c.setConversationId(conversationId);
        c.setParentConversationId("conv-root");
        c.setAgentId(agentId);
        c.setTitle(title);
        c.setLastActiveTime(LocalDateTime.now());
        return c;
    }

    @Test
    void reportsNoContextWhenConversationUnknown() {
        String out = tool.listSubagents(null);
        assertTrue(out.contains("no conversation context"), out);
    }

    @Test
    void reportsEmptyWhenNoSessions() {
        ToolExecutionContext.set("conv-root", "tester");
        when(conversationMapper.selectList(any())).thenReturn(List.of());
        String out = tool.listSubagents(null);
        assertTrue(out.contains("No sub-agent sessions for this conversation"), out);
    }

    @Test
    void listsPersistedSessionsWithSessionIds() {
        ToolExecutionContext.set("conv-root", "tester");
        when(conversationMapper.selectList(any())).thenReturn(List.of(
                child("child-1", 11L, "research the topic"),
                child("child-2", 22L, "draft the summary")));

        String out = tool.listSubagents(null);

        assertTrue(out.contains("session_id=child-1"), out);
        assertTrue(out.contains("session_id=child-2"), out);
        assertTrue(out.contains("send_to_subagent"), out);
        // Finished sessions stay discoverable even though the live registry is empty.
        assertTrue(out.contains("idle"), out);
    }

    @Test
    void overlaysLiveStatusOnPersistedSession() {
        ToolExecutionContext.set("conv-root", "tester");
        when(conversationMapper.selectList(any())).thenReturn(List.of(
                child("child-1", 11L, "research the topic"),
                child("child-2", 22L, "draft the summary")));
        // child-2 is still running according to the live registry.
        registry.register("conv-root", "child-2", 22L, "draft the summary", null);

        String out = tool.listSubagents(null);

        assertTrue(out.contains("session_id=child-2"), out);
        assertTrue(out.contains("running"), "live child should show running status: " + out);
        // child-1 (no live record) renders as idle, child-2 as running — child-2 not duplicated.
        assertEquals(1, out.split("session_id=child-2", -1).length - 1, "child-2 listed once: " + out);
    }

    @Test
    void prefersDelegationRootOverCurrentConversation() {
        // Inside a delegated layer, the tree root is the human-facing conversation.
        ToolExecutionContext.set("child-conv", "tester");
        DelegationContext.enter("child-conv", java.util.Set.of(), "conv-root", "sa-1", 1);
        try {
            when(conversationMapper.selectList(any())).thenReturn(List.of(child("child-1", 11L, "research")));
            String out = tool.listSubagents(null);
            assertTrue(out.contains("session_id=child-1"), out);
            assertFalse(out.contains("no conversation context"), out);
        } finally {
            DelegationContext.exit();
        }
    }
}
