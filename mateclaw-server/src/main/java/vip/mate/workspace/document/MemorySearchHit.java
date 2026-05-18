package vip.mate.workspace.document;

/**
 * One snippet-level hit from {@link WorkspaceFileService#searchSnippets}.
 *
 * @param filename   Workspace filename the hit was extracted from (e.g.
 *                   {@code MEMORY.md}, {@code memory/2026-05-10.md}).
 * @param lineNumber 1-based line number of the hit within {@code filename}.
 * @param snippet    The original line clipped to {@code head(80) + match +
 *                   tail(80)} and with each matched term wrapped in
 *                   {@code [[...]]} for downstream highlighting.
 * @param score      Composite relevance: number of terms matched on this line
 *                   weighted by per-file importance (see
 *                   {@code WorkspaceFileService#fileWeight}).
 */
public record MemorySearchHit(String filename, int lineNumber, String snippet, double score) {
}
