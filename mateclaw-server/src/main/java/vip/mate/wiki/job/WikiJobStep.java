package vip.mate.wiki.job;

/**
 * Logical steps within a wiki processing job, used for per-step model routing.
 */
public enum WikiJobStep {
    ROUTE, CREATE_PAGE, MERGE_PAGE, ENRICH, SUMMARY, ENTITY_EXTRACTION
}
