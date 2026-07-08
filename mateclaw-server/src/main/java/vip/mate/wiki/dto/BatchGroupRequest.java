package vip.mate.wiki.dto;

import java.util.List;

/**
 * Batch raw-material group reassignment payload. {@code groupId} null clears the grouping.
 */
public record BatchGroupRequest(List<Long> rawIds, Long groupId) {}
