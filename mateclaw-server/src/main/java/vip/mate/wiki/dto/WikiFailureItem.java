package vip.mate.wiki.dto;

import java.time.LocalDateTime;

/**
 * Cross-KB projection of a raw material that needs operator attention —
 * failed, partial, or completed-but-degraded (a warning was recorded). Powers
 * the centralized Wiki failure list so operators can triage background
 * processing problems without opening each knowledge base in turn.
 */
public record WikiFailureItem(
        Long rawId,
        Long kbId,
        String kbName,
        Long workspaceId,
        String title,
        String processingStatus,
        String errorCode,
        String errorMessage,
        String warningCode,
        String warningMessage,
        LocalDateTime updateTime
) {}
