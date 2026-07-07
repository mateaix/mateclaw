package vip.mate.wiki.dto;

import java.time.LocalDateTime;

/**
 * Source group view, enriched with the live raw-material count.
 */
public record SourceGroupVO(Long id, Long kbId, String alias, String path, String fileFilter,
                             String cronExpr, Integer enabled, Long rawCount,
                             LocalDateTime lastScanAt) {}
