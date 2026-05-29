package vip.mate.approval.grant;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import vip.mate.MateClawApplication;
import vip.mate.approval.event.ApprovalResolutionEvent;
import vip.mate.approval.grant.entity.ApprovalGrant;
import vip.mate.approval.grant.entity.ApprovalResolutionLog;
import vip.mate.approval.grant.repository.ApprovalGrantMapper;
import vip.mate.approval.grant.repository.ApprovalResolutionLogMapper;
import vip.mate.workflow.runtime.StubAgentInvokerConfig;
import vip.mate.workspace.conversation.event.ConversationDeletedEvent;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end wiring test for PR-2: publishing a generic approval resolution event
 * lands one row in {@code mate_approval_resolution_log}, and publishing a
 * {@code ConversationDeletedEvent} soft-revokes UNTIL_CONVERSATION_END grants
 * (leaving other-scope grants alone).
 * <p>
 * Reuses the same test profile shape as {@code AgentLifecycleTriggerTest}:
 * isolated in-memory H2 per test (no dev DB file), {@code webEnvironment=NONE}
 * (no WebSocket container), and the workflow stub configs so the workflow
 * trigger bridge doesn't pull in real graph dependencies.
 */
@SpringBootTest(
        classes = MateClawApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:approval_pr2_${random.uuid};MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.ai.dashscope.api-key=test-key",
        "spring.main.web-application-type=none",
        "mateclaw.workflow.trigger.async-dispatch=false"
})
@Import(StubAgentInvokerConfig.class)
class ApprovalGrantPr2IT {

    @Autowired private ApplicationEventPublisher publisher;
    @Autowired private ApprovalResolutionLogMapper resolutionMapper;
    @Autowired private ApprovalGrantMapper grantMapper;

    @Test
    @DisplayName("USER_MANUAL ApprovalResolutionEvent → one resolution_log row written by the listener.")
    void userManualEventLandsRow() {
        long before = resolutionMapper.selectCount(null);

        publisher.publishEvent(new ApprovalResolutionEvent(
                "pid-it-1", "conv-it-1", "agent-it-1", "user-it-1",
                "read_file", "{\"path\":\"a.txt\"}", "LOW",
                "[{\"ruleId\":\"shell.read\",\"severity\":\"LOW\"}]",
                "USER_MANUAL", null));

        List<ApprovalResolutionLog> rows = resolutionMapper.selectList(
                Wrappers.<ApprovalResolutionLog>lambdaQuery()
                        .eq(ApprovalResolutionLog::getPendingId, "pid-it-1"));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getDecisionSource()).isEqualTo("USER_MANUAL");
        assertThat(rows.get(0).getRuleIds()).isEqualTo("shell.read");
        assertThat(resolutionMapper.selectCount(null)).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("TIMEOUT ApprovalResolutionEvent → resolution_log row carries decision_source=TIMEOUT.")
    void timeoutEventLandsRow() {
        publisher.publishEvent(new ApprovalResolutionEvent(
                "pid-it-timeout", "conv-it-timeout", "agent-it-timeout", null,
                "execute_shell_command", "ls /tmp", "MEDIUM", null,
                "TIMEOUT", null));

        ApprovalResolutionLog row = resolutionMapper.selectOne(
                Wrappers.<ApprovalResolutionLog>lambdaQuery()
                        .eq(ApprovalResolutionLog::getPendingId, "pid-it-timeout"));
        assertThat(row).isNotNull();
        assertThat(row.getDecisionSource()).isEqualTo("TIMEOUT");
    }

    @Test
    @DisplayName("ConversationDeletedEvent → UNTIL_CONVERSATION_END grant revoked, ALWAYS grant untouched.")
    void conversationDeleteRevokesScopedGrantOnly() {
        String conversationId = "conv-it-delete";
        long workspaceId = 555L;

        ApprovalGrant conversationGrant = newGrant(workspaceId, "CONVERSATION",
                conversationId, "read_file", "ALWAYS", "UNTIL_CONVERSATION_END");
        ApprovalGrant agentGrant = newGrant(workspaceId, "AGENT",
                "agent-it-delete", "read_file", "ALWAYS", "ALWAYS");
        grantMapper.insert(conversationGrant);
        grantMapper.insert(agentGrant);

        publisher.publishEvent(new ConversationDeletedEvent(conversationId));

        ApprovalGrant convAfter = grantMapper.selectById(conversationGrant.getId());
        ApprovalGrant agentAfter = grantMapper.selectById(agentGrant.getId());
        assertThat(convAfter.getRevoked()).isEqualTo(1);
        assertThat(agentAfter.getRevoked()).isEqualTo(0);
    }

    /** Builds a minimal grant row; create/update timestamps default in the DB. */
    private ApprovalGrant newGrant(long workspaceId, String scopeType, String scopeId,
                                   String toolName, String maxSeverity, String grantKind) {
        ApprovalGrant g = new ApprovalGrant();
        g.setWorkspaceId(workspaceId);
        g.setScopeType(scopeType);
        g.setScopeId(scopeId);
        g.setToolName(toolName);
        g.setRuleId(null);
        g.setMaxSeverity(maxSeverity);
        g.setGrantKind(grantKind);
        g.setGrantedBy(1L);
        g.setGrantedAt(LocalDateTime.now());
        g.setRevoked(0);
        g.setDeleted(0);
        return g;
    }
}
