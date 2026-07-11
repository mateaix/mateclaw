package vip.mate.wiki.dto;

import java.util.List;

/**
 * Batch raw-material group reassignment payload. {@code groupId} null clears the grouping.
 * <p>
 * {@code rawIds} is capped at {@link #MAX_RAW_IDS} entries — larger batches are rejected
 * with a 400 before hitting the database to avoid unbounded IN-clause DoS.
 */
public record BatchGroupRequest(List<Long> rawIds, Long groupId) {

    public static final int MAX_RAW_IDS = 500;
}
