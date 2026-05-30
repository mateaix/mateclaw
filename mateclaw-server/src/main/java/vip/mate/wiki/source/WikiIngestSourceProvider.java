package vip.mate.wiki.source;

import vip.mate.wiki.model.WikiKnowledgeBaseEntity;
import vip.mate.wiki.service.WikiDirectoryScanService;

/**
 * Pluggable source of raw material for a knowledge base. The watcher iterates
 * KBs and asks each registered provider whether it {@link #supports} the KB,
 * then {@link #sync}s it. The filesystem implementation ships today; API /
 * message-queue sources can be added later by implementing this interface
 * without touching the watcher.
 *
 * @author MateClaw Team
 */
public interface WikiIngestSourceProvider {

    /** Stable source-type id, e.g. {@code filesystem} / {@code api} / {@code mq}. */
    String sourceType();

    /** Whether this provider can sync the given KB (e.g. it has the relevant config). */
    boolean supports(WikiKnowledgeBaseEntity kb);

    /**
     * Pull new / changed material for the KB and ingest it, returning the
     * scan-style result (scanned / added / skipped / errors).
     */
    WikiDirectoryScanService.ScanResult sync(WikiKnowledgeBaseEntity kb);
}
