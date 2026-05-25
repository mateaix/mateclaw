package vip.mate.wiki.service;

/**
 * Resolves {@code rawId -> rawTitle} for embedding-time enrichment.
 * <p>
 * Implementations may be naive (one DB hit per call), batch-preloaded for a
 * given job, or backed by an in-memory snapshot shared with an ingest-scope
 * page index. Callers must tolerate {@code null} for unknown / deleted ids.
 */
@FunctionalInterface
public interface RawTitleLookup {

    /** @return raw material title, or {@code null} when the id is unknown */
    String titleFor(Long rawId);
}
