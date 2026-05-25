package vip.mate.wiki.service;

import vip.mate.wiki.dto.RawTitleRef;
import vip.mate.wiki.repository.WikiRawMaterialMapper;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Factory helpers for {@link RawTitleLookup}.
 */
public final class RawTitleLookups {

    private RawTitleLookups() {}

    /** Lookup that always returns {@code null}; useful for callers without raw context. */
    public static RawTitleLookup empty() {
        return id -> null;
    }

    /** Lookup backed by a pre-resolved map (e.g. from an ingest-scope snapshot). */
    public static RawTitleLookup of(Map<Long, String> titlesById) {
        Map<Long, String> snapshot = titlesById == null ? Map.of() : Map.copyOf(titlesById);
        return snapshot::get;
    }

    /**
     * Preload titles for the given ids in a single batch query and return a
     * lookup over the resulting map. Unknown ids resolve to {@code null}.
     */
    public static RawTitleLookup preload(WikiRawMaterialMapper mapper, Collection<Long> rawIds) {
        if (mapper == null || rawIds == null || rawIds.isEmpty()) {
            return empty();
        }
        Map<Long, String> titles = new HashMap<>(rawIds.size());
        for (RawTitleRef ref : mapper.selectBatchTitles(rawIds)) {
            titles.put(ref.id(), ref.title());
        }
        return of(titles);
    }
}
