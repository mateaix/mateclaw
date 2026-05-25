package vip.mate.wiki.service;

import org.springframework.stereotype.Component;
import vip.mate.wiki.model.WikiChunkEntity;

/**
 * Produces the text fed to the embedding model for a chunk.
 * <p>
 * Naive content-only embeddings make short or context-poor chunks (e.g. a
 * standalone sentence like "accuracy improved by 12%") near-indistinguishable
 * in vector space. Prefixing the model input with already-available metadata —
 * source title, header breadcrumb, source section, page number — preserves
 * the semantic neighborhood the chunk came from without changing the storage
 * model.
 * <p>
 * Bump {@link #CURRENT_INPUT_VERSION} whenever the prefix format changes. The
 * embedding pass treats any chunk whose stored {@code embedding_text_version}
 * differs from the current value as stale and re-embeds it.
 */
@Component
public class WikiEmbeddingInputBuilder {

    /**
     * Version tag stamped onto every chunk that this builder embeds.
     * Increment when the prefix format below changes in a way that should
     * trigger a re-embed pass. The string is opaque; "v1", "v2", ... is fine.
     */
    public static final String CURRENT_INPUT_VERSION = "v1";

    /**
     * Build the embedding input string for a chunk. Metadata fields that are
     * null or blank are skipped so empty values never produce stray headers.
     * Falls back to the chunk content alone when no metadata is available.
     */
    public String build(WikiChunkEntity chunk, RawTitleLookup lookup) {
        if (chunk == null) {
            return "";
        }
        String content = chunk.getContent() == null ? "" : chunk.getContent();
        String prefix = buildPrefix(chunk, lookup);
        return prefix.isEmpty() ? content : prefix + content;
    }

    /**
     * Build only the metadata prefix for a chunk. Useful when callers need to
     * split content into sub-segments and prepend the prefix to each one so
     * the metadata participates in every per-segment embedding before pooling.
     * Returns an empty string when no metadata is available; otherwise ends
     * with a blank line so the content reads as a separate paragraph.
     */
    public String buildPrefix(WikiChunkEntity chunk, RawTitleLookup lookup) {
        if (chunk == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        String rawTitle = (lookup == null || chunk.getRawId() == null)
                ? null : lookup.titleFor(chunk.getRawId());
        appendLine(sb, "Source", rawTitle);
        appendLine(sb, "Section", chunk.getHeaderBreadcrumb());
        appendLine(sb, "Subsection", chunk.getSourceSection());
        if (chunk.getPageNumber() != null) {
            appendLine(sb, "Page", String.valueOf(chunk.getPageNumber()));
        }
        if (sb.length() == 0) {
            return "";
        }
        sb.append('\n');
        return sb.toString();
    }

    /** @return the version tag this builder stamps onto each chunk it embeds */
    public String currentVersion() {
        return CURRENT_INPUT_VERSION;
    }

    private static void appendLine(StringBuilder sb, String label, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        sb.append(label).append(": ").append(value.strip()).append('\n');
    }
}
