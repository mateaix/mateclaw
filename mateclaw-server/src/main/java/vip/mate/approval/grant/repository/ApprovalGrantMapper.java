package vip.mate.approval.grant.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import vip.mate.approval.grant.entity.ApprovalGrant;

import java.util.List;

/**
 * Mapper for {@link ApprovalGrant}.
 * <p>
 * BaseMapper covers ordinary CRUD; {@link #findFirstMatching} is a custom query
 * defined in {@code ApprovalGrantMapper.xml} that returns the best-matching active
 * grant for a tool invocation, ordered by scope priority and specificity.
 */
@Mapper
public interface ApprovalGrantMapper extends BaseMapper<ApprovalGrant> {

    /**
     * Returns the single best grant that authorizes the given tool invocation, or
     * {@code null} if none applies. Matching rules (see {@code ApprovalGrantMapper.xml}):
     *
     * <ul>
     *   <li>{@code workspace_id} must equal {@code workspaceId} (tenant isolation, mandatory).</li>
     *   <li>Not revoked, not deleted, not expired.</li>
     *   <li>{@code max_severity} must be at least as high as {@code evalSeverity}.</li>
     *   <li>{@code tool_name} is NULL or equals {@code toolName}.</li>
     *   <li>{@code rule_id} is NULL or is in {@code candidateRuleIds} (when the list is non-empty).</li>
     *   <li>One of the scope clauses must match: CONVERSATION+conversationId / AGENT+agentId /
     *       USER+userId / WORKSPACE+workspaceScopeId.</li>
     * </ul>
     *
     * Order: scope priority CONVERSATION &gt; AGENT &gt; USER &gt; WORKSPACE,
     * then rule-id-specific over rule-id-null, then tool-name-specific over null. {@code LIMIT 1}.
     *
     * @param workspaceScopeId {@code String.valueOf(workspaceId)} — pre-converted to avoid
     *                         dialect-specific CAST in SQL (H2 vs MySQL).
     * @param candidateRuleIds list of GuardFinding ruleIds for the current invocation; may be empty
     *                         or null, in which case only {@code rule_id IS NULL} grants match.
     */
    ApprovalGrant findFirstMatching(
            @Param("workspaceId") Long workspaceId,
            @Param("userId") String userId,
            @Param("agentId") String agentId,
            @Param("conversationId") String conversationId,
            @Param("workspaceScopeId") String workspaceScopeId,
            @Param("toolName") String toolName,
            @Param("candidateRuleIds") List<String> candidateRuleIds,
            @Param("evalSeverity") String evalSeverity);

    /**
     * Soft-revokes every active {@code UNTIL_CONVERSATION_END} grant attached to the given
     * conversation. Called by {@code ConversationLifecycleListener} on
     * {@code ConversationDeletedEvent} (PR-2).
     */
    int revokeUntilConversationEnd(@Param("conversationId") String conversationId);
}
