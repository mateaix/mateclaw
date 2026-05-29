package vip.mate.approval.grant.listener;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import vip.mate.approval.event.ApprovalResolutionEvent;
import vip.mate.approval.grant.WorkspaceLookupCache;
import vip.mate.approval.grant.entity.ApprovalResolutionLog;
import vip.mate.approval.grant.repository.ApprovalResolutionLogMapper;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Records one row in {@code mate_approval_resolution_log} per final
 * human-approval decision (USER_MANUAL / TIMEOUT), complementing the rows
 * written directly by {@code ApprovalGrantResolver} for HARD_BLOCK and
 * AUTO_GRANT.
 * <p>
 * The listener runs out-of-tx (the publisher fires events from an
 * {@code afterCommit} hook), so a DB write failure here cannot roll back the
 * already-committed approval state. We log the failure and continue — losing
 * one resolution-log row is far less harmful than re-opening the approval row
 * for double-resolve.
 *
 * <p>Workspace resolution goes through {@link WorkspaceLookupCache} so we get
 * the same conversation→workspace mapping the resolver uses on the hot path,
 * with the same null-fallback behavior: a deleted conversation produces a row
 * with {@code workspace_id = null}, which is allowed by the V128 schema.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApprovalResolutionLogListener {

    private static final int ARGS_PREVIEW_MAX = 500;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ApprovalResolutionLogMapper resolutionMapper;
    private final WorkspaceLookupCache workspaceLookupCache;

    @EventListener
    public void onApprovalResolved(ApprovalResolutionEvent event) {
        try {
            ApprovalResolutionLog row = new ApprovalResolutionLog();
            row.setWorkspaceId(workspaceLookupCache.resolveByConversation(event.conversationId()));
            row.setConversationId(event.conversationId());
            row.setAgentId(event.agentId());
            row.setUserId(event.userId());
            row.setToolName(event.toolName());
            row.setMaxSeverity(event.maxSeverity());
            row.setRuleIds(extractRuleIds(event.findingsJson()));
            row.setDecisionSource(event.decisionSource());
            row.setGrantId(null);
            row.setPendingId(event.pendingId());
            row.setArgsPreview(previewArgs(event.toolArguments()));
            row.setNote(event.resolutionNote());

            resolutionMapper.insert(row);
        } catch (Exception e) {
            log.warn("[APPROVAL] ApprovalResolutionLogListener failed to record {} for pending {}: {}",
                    event.decisionSource(), event.pendingId(), e.getMessage());
        }
    }

    /**
     * Pulls {@code ruleId}s out of the serialized findings JSON captured at
     * {@code createPending} time. The JSON is the standard
     * {@code GuardFinding.toMap()} array form (a {@code List<Map<String, Object>>}),
     * so we read the list and pick the {@code "ruleId"} key out of each map.
     * Returns {@code null} on missing or unparseable input — the row still gets
     * written, just without rule-id provenance.
     */
    private static String extractRuleIds(String findingsJson) {
        if (findingsJson == null || findingsJson.isBlank()) {
            return null;
        }
        try {
            List<Map<String, Object>> findings = OBJECT_MAPPER.readValue(
                    findingsJson, new TypeReference<>() {});
            String joined = findings.stream()
                    .map(m -> m.get("ruleId"))
                    .filter(Objects::nonNull)
                    .map(Object::toString)
                    .filter(s -> !s.isBlank())
                    .distinct()
                    .collect(Collectors.joining(","));
            return joined.isEmpty() ? null : joined;
        } catch (Exception e) {
            log.debug("[APPROVAL] Failed to parse findingsJson for rule_ids extraction: {}", e.getMessage());
            return null;
        }
    }

    private static String previewArgs(String raw) {
        if (raw == null) return null;
        return raw.length() <= ARGS_PREVIEW_MAX ? raw : raw.substring(0, ARGS_PREVIEW_MAX);
    }
}
