package vip.mate.tool.builtin;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.mate.agent.AgentService;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.workspace.conversation.model.ConversationEntity;
import vip.mate.workspace.conversation.repository.ConversationMapper;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SessionSendTool} — the multi-turn "send" leg of the
 * spawn / send / list delegation triad.
 *
 * @author MateClaw Team
 */
@ExtendWith(MockitoExtension.class)
class SessionSendToolTest {

    @Mock AgentService agentService;
    @Mock ConversationMapper conversationMapper;

    @InjectMocks SessionSendTool tool;

    @BeforeAll
    static void initMyBatisPlusCache() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new org.apache.ibatis.session.Configuration(), ""),
                ConversationEntity.class);
    }

    @BeforeEach
    void setUp() {
        ToolExecutionContext.clear();
        while (DelegationContext.currentDepth() > 0) {
            DelegationContext.exit();
        }
    }

    @AfterEach
    void tearDown() {
        ToolExecutionContext.clear();
        while (DelegationContext.currentDepth() > 0) {
            DelegationContext.exit();
        }
    }

    private static ConversationEntity child(String conversationId, String parentConversationId, Long agentId) {
        ConversationEntity c = new ConversationEntity();
        c.setConversationId(conversationId);
        c.setParentConversationId(parentConversationId);
        c.setAgentId(agentId);
        return c;
    }

    @Test
    void rejectsUnknownSession() {
        ToolExecutionContext.set("conv-root", "tester");
        when(conversationMapper.selectOne(any())).thenReturn(null);
        String out = tool.sendToSubagent("child-x", "do more", null);
        assertTrue(out.contains("Unknown session_id"), out);
        verify(agentService, never()).chat(any(), any(), any(), any());
    }

    @Test
    void rejectsNonSubagentSession() {
        ToolExecutionContext.set("conv-root", "tester");
        when(conversationMapper.selectOne(any())).thenReturn(child("conv-root", null, 7L));
        String out = tool.sendToSubagent("conv-root", "do more", null);
        assertTrue(out.contains("not a sub-agent session"), out);
        verify(agentService, never()).chat(any(), any(), any(), any());
    }

    @Test
    void rejectsSessionOwnedByAnotherConversation() {
        ToolExecutionContext.set("conv-root", "tester");
        // Child's parent is a different conversation than the caller.
        when(conversationMapper.selectOne(any())).thenReturn(child("child-1", "other-conv", 7L));
        String out = tool.sendToSubagent("child-1", "do more", null);
        assertTrue(out.contains("does not belong to this conversation"), out);
        verify(agentService, never()).chat(any(), any(), any(), any());
    }

    @Test
    void rejectsWhenDepthLimitReached() {
        ToolExecutionContext.set("conv-root", "tester");
        // Simulate being already at the max delegation depth.
        DelegationContext.enter("conv", java.util.Set.of(), "root", "sa", DelegateAgentTool.MAX_DELEGATION_DEPTH);
        try {
            String out = tool.sendToSubagent("child-1", "do more", null);
            assertTrue(out.contains("depth limit"), out);
            verify(conversationMapper, never()).selectOne(any());
        } finally {
            DelegationContext.exit();
        }
    }

    @Test
    void continuesChildSessionForOwningConversation() {
        ToolExecutionContext.set("conv-root", "tester");
        when(conversationMapper.selectOne(any())).thenReturn(child("child-1", "conv-root", 7L));
        when(agentService.chat(eq(7L), eq("refine it"), eq("child-1"), any(ChatOrigin.class)))
                .thenReturn("refined result");

        String out = tool.sendToSubagent("child-1", "refine it", null);

        assertTrue(out.contains("Sub-agent reply"), out);
        assertTrue(out.contains("child-1"), out);
        assertTrue(out.contains("refined result"), out);
        verify(agentService).chat(eq(7L), eq("refine it"), eq("child-1"), any(ChatOrigin.class));
    }
}
